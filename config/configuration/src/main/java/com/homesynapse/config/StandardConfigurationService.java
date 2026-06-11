/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homesynapse.event.ConfigErrorEvent;
import com.homesynapse.event.ConfigSectionReloadedEvent;
import com.homesynapse.event.ConfigValidationCompletedEvent;
import com.homesynapse.event.ConfigurationValidationException;
import com.homesynapse.event.DomainEvent;
import com.homesynapse.event.EventDraft;
import com.homesynapse.event.EventOrigin;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventPublisher;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.SequenceConflictException;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.platform.identity.SystemId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * {@link ConfigurationService} implementation — the Doc 06 configuration
 * lifecycle: the §3.1 load pipeline (M6.1), the §3.3 hot-reload atomic swap
 * and the §3.5 UI/API write path (M6.4).
 *
 * <h2>Load pipeline (Doc 06 §3.1)</h2>
 *
 * <p>{@link #load()} reads and parses the AMD-71 layout (via
 * {@link YamlLoader}), runs the AMD-67 migration chain on a major mismatch,
 * merges JSON Schema defaults, validates the merged whole against the
 * composed schema (AMD-71 §2.4 compose-after-merge), applies the §3.6
 * startup error model, constructs the immutable {@link ConfigModel}, and
 * publishes the AMD-70 observability events.</p>
 *
 * <h2>Startup error model (DP-2 / Doc 06 §3.6)</h2>
 *
 * <p>On {@link #load()}: {@link Severity#ERROR} issues revert the offending
 * key to its schema default — the system starts degraded but functional
 * (INV-RF-06). {@link Severity#FATAL} issues abort with
 * {@link ConfigurationLoadException}. Structural FATALs from the parse
 * stage abort before any validation pass runs.</p>
 *
 * <h2>Migration trigger (AMD-67-INV-02) and write-back (§3.7 step 7)</h2>
 *
 * <p>Only a persisted {@code configSchemaMajor} below the declared major
 * triggers the migrator chain; a minor-only mismatch never migrates. The
 * chain selects every registered migrator whose {@code fromMajor} lies in
 * {@code [persistedMajor, declaredMajor)}, ordered by
 * {@code (fromMajor, fromMinor)} — the reference semantics pinned by
 * {@code ConfigMigratorChainTest}. A persisted major NEWER than the
 * declared major is FATAL (migration is forward-only, §3.7). When a chain
 * ran and validation found no FATAL, {@link #load()} persists the migrated
 * document (M6.4, R2): the original is first copied to
 * {@code homesynapse.yaml.pre-migration-v{major}.{minor}} (never
 * auto-deleted, INV-CE-06), then the migrated YAML — stamped with the
 * declared pair — replaces the file atomically and the
 * {@code fileModifiedAt} token is refreshed from the new file. A write-back
 * failure logs a WARNING and the load continues on the in-memory migration:
 * the idempotent chain simply re-runs on the next boot against the intact
 * original.</p>
 *
 * <h2>Reload (Doc 06 §3.3 — M6.4)</h2>
 *
 * <p>{@link #reload()} re-runs the full §3.1 pipeline against disk, then:
 * any FATAL <em>or</em> ERROR in the candidate rejects the whole reload
 * with {@link ConfigurationReloadException} — the active model is never
 * degraded, no events are published, and no listener classification is
 * applied (C5 / INV-RF-06; stricter than startup because a prior good
 * state exists). Otherwise the candidate is diffed against the active
 * model ({@link ConfigModelDiff}, DP-5), every changed section is
 * classified — registered {@link ConfigurationChangeListener} first,
 * else the most restrictive per-property {@code x-reload} among the
 * section's changed keys, with unannotated properties defaulting to
 * {@link ReloadClassification#PROCESS_RESTART} (AMD-66 §2.3) — and the
 * active model is <em>atomically swapped</em>: one volatile reference
 * assignment of an immutable model under the reload lock, so every
 * in-flight reader observes wholly-old or wholly-new, never a torn mix
 * (DP-1). A throwing listener rejects the candidate (AMD-66 §4). M6.4
 * <em>reports</em> classifications via the {@link ReloadResult} and the
 * {@code config.section_reloaded} events; acting on them (integration or
 * process restarts) is the M9 supervisor's concern.</p>
 *
 * <h2>Write path (Doc 06 §3.5 — M6.4)</h2>
 *
 * <p>{@link #write(List, Instant)} serializes behind the same lock:
 * optimistic-concurrency check of the caller's {@code fileModifiedAt}
 * token against the file's real mtime (§6.7 —
 * {@link ConcurrentModificationException} on mismatch), mutation of the
 * freshly re-parsed on-disk document (INV-CE-01 — the file is truth; a
 * {@code null} mutation value removes the key, reverting it to the schema
 * default), validation of the mutated copy (FATAL/ERROR →
 * {@link ConfigurationValidationException}, file untouched), the REC-131
 * one-time first-write backup, an atomic
 * write-temp-fsync-rename flush ({@link AtomicYamlWriter}, §6.8 — a
 * failure at any step leaves the prior file intact), and finally the
 * reload pipeline over the new file — which is also the only event leg:
 * the write itself publishes nothing (DP-7). Comment loss on programmatic
 * writes is the documented Locked-doc limitation (§3.5).</p>
 *
 * <h2>Observability (AMD-70; rulings 2026-06-10)</h2>
 *
 * <p>{@code config.validation_completed} fires exactly once per completed
 * {@link #load()} validation pass — including a pass that found FATAL
 * issues. After the same pass, one {@code config_error} event is published
 * per {@link Severity#ERROR} issue (M6.4, R1 — Doc 06 §4.5 "at startup";
 * never from {@link #reload()}), with the §12.4 fence: an
 * {@code x-sensitive} path publishes {@code "[REDACTED]"} for the message
 * and {@code "(none)"} for the default. {@code config.section_reloaded}
 * fires once per section actually changed by a reload, after listener
 * classification, and is the reload path's ONLY event — the value-bearing
 * {@code ConfigChangedEvent} stays production-unpublished (DP-7, ruling
 * R4: Doc 06 §12.4 forbids configuration values in event payloads). All
 * three publish identically: DIAGNOSTIC priority,
 * {@link EventOrigin#SYSTEM}, {@code null} {@code eventTime}, the system
 * subject, a {@code null} actor, via {@link EventPublisher#publishRoot}.
 * They are observability-only (AMD-70-INV-01): a publish failure is logged
 * and never fails the load, reload, or write.</p>
 *
 * <h2>Listener registration (AMD-66 §2.4, ruled List form)</h2>
 *
 * <p>{@link ConfigurationChangeListener}s are registered at construction
 * from a {@code List}; this service builds the internal map keyed by
 * {@link ConfigurationChangeListener#sectionPath()} and rejects a
 * duplicate section path with {@link IllegalArgumentException}. Listeners
 * are invoked synchronously by the reload pipeline, before any event is
 * published (AMD-66-INV-02), and never mutate the model
 * (AMD-66-INV-01).</p>
 *
 * <h2>Thread safety</h2>
 *
 * <p>{@link #getCurrentModel()} and {@link #getSection(String)} are
 * non-blocking volatile reads (DP-1). One {@link ReentrantLock} serializes
 * {@link #load()}, {@link #reload()}, and {@link #write(List, Instant)}
 * (DP-2); the write path's internal reload runs under the already-held
 * lock. Holding the lock during file I/O is the §3.5 design — the lock
 * exists precisely to serialize access to the configuration file.</p>
 */
final class StandardConfigurationService implements ConfigurationService {

    private static final Logger log =
            LoggerFactory.getLogger(StandardConfigurationService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Top-level document key carrying the AMD-67 pair (DP-1, object form). */
    private static final String SCHEMA_VERSION_KEY = "schema_version";

    /** AMD-71 §2.1: the regenerable composed-schema cache location. */
    private static final String SCHEMAS_CACHE_DIRECTORY = "schemas";
    private static final String SCHEMA_CACHE_FILE_NAME = "config.schema.json";

    /** REC-131 first-write backup name prefix (absence check = "first"). */
    private static final String WRITE_BACKUP_PREFIX =
            YamlLoader.ROOT_DOCUMENT_NAME + ".bak.";

    /** §3.7 step-7 pre-migration backup name prefix (never auto-deleted). */
    private static final String PRE_MIGRATION_BACKUP_PREFIX =
            YamlLoader.ROOT_DOCUMENT_NAME + ".pre-migration-v";

    /**
     * Filesystem-safe ISO-8601 basic-format stamp for backup names — the
     * extended form's colons are illegal in Windows file names.
     */
    private static final DateTimeFormatter BACKUP_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                    .withZone(ZoneOffset.UTC);

    /** §12.4 fence literals for sensitive config_error payloads (DP-10). */
    private static final String REDACTED_MESSAGE = "[REDACTED]";
    private static final String NO_DEFAULT = "(none)";

    private final Path configDir;
    private final int declaredSchemaMajor;
    private final int declaredSchemaMinor;
    private final Clock clock;
    private final SystemId systemId;
    private final EventPublisher eventPublisher;
    private final SchemaRegistry schemaRegistry;
    private final ConfigValidator validator;
    private final List<ConfigMigrator> migrators;

    /**
     * Listener registrations keyed by section path (AMD-66 §2.4). Built and
     * frozen at construction; consumed by the reload pipeline.
     */
    private final Map<String, ConfigurationChangeListener> listenersBySection;

    /**
     * Serializes load/reload/write (DP-2). Re-entrant so the write path's
     * step-7 reload runs under the already-held lock.
     */
    private final ReentrantLock reloadLock = new ReentrantLock();

    private volatile ConfigModel activeModel;

    /**
     * Creates the service with its composition-root-wired collaborators.
     *
     * @param configDir           the resolved configuration directory from
     *                            {@code PlatformPaths.configDir()}, injected
     *                            as a {@code Path} (DP-3 / AMD-71-A — no
     *                            config→platform module edge); never
     *                            {@code null}
     * @param declaredSchemaMajor the config-document schema major this
     *                            runtime declares (AMD-67); the migration
     *                            trigger threshold; must be {@code >= 1} and
     *                            must match the pair wired into
     *                            {@code StandardSchemaRegistry}
     * @param declaredSchemaMinor the declared config-document schema minor;
     *                            must be {@code >= 0}
     * @param clock               time source for {@code loadedAt}, the
     *                            absent-file {@code fileModifiedAt} fallback
     *                            (DP-5, §4c — never the ambient clock), the
     *                            change-set timestamp, and the first-write
     *                            backup stamp; never {@code null}
     * @param systemId            this installation's system identity — the
     *                            subject of configuration observability
     *                            events; never {@code null}
     * @param eventPublisher      publisher for the AMD-70 observability
     *                            events; never {@code null}
     * @param schemaRegistry      the composed-schema source (AMD-71 §2.4);
     *                            never {@code null}
     * @param validator           the allErrors schema validator;
     *                            never {@code null}
     * @param migrators           registered forward-only migrators; may be
     *                            empty (no production migrator exists);
     *                            never {@code null}
     * @param listeners           AMD-66 listener registrations; at most one
     *                            per section path; never {@code null}
     * @throws IllegalArgumentException if the declared pair is out of range
     *                                  or two listeners share a section path
     */
    StandardConfigurationService(Path configDir,
                                 int declaredSchemaMajor,
                                 int declaredSchemaMinor,
                                 Clock clock,
                                 SystemId systemId,
                                 EventPublisher eventPublisher,
                                 SchemaRegistry schemaRegistry,
                                 ConfigValidator validator,
                                 List<ConfigMigrator> migrators,
                                 List<ConfigurationChangeListener> listeners) {
        this.configDir = Objects.requireNonNull(configDir, "configDir must not be null");
        if (declaredSchemaMajor < 1) {
            throw new IllegalArgumentException(
                    "declaredSchemaMajor must be >= 1: " + declaredSchemaMajor);
        }
        if (declaredSchemaMinor < 0) {
            throw new IllegalArgumentException(
                    "declaredSchemaMinor must be >= 0: " + declaredSchemaMinor);
        }
        this.declaredSchemaMajor = declaredSchemaMajor;
        this.declaredSchemaMinor = declaredSchemaMinor;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.systemId = Objects.requireNonNull(systemId, "systemId must not be null");
        this.eventPublisher =
                Objects.requireNonNull(eventPublisher, "eventPublisher must not be null");
        this.schemaRegistry =
                Objects.requireNonNull(schemaRegistry, "schemaRegistry must not be null");
        this.validator = Objects.requireNonNull(validator, "validator must not be null");
        this.migrators = List.copyOf(
                Objects.requireNonNull(migrators, "migrators must not be null"));
        Objects.requireNonNull(listeners, "listeners must not be null");
        Map<String, ConfigurationChangeListener> bySection = new LinkedHashMap<>();
        for (ConfigurationChangeListener listener : listeners) {
            Objects.requireNonNull(listener, "listeners must not contain null");
            ConfigurationChangeListener previous =
                    bySection.putIfAbsent(listener.sectionPath(), listener);
            if (previous != null) {
                throw new IllegalArgumentException(
                        "duplicate ConfigurationChangeListener section path: "
                                + listener.sectionPath());
            }
        }
        this.listenersBySection = Map.copyOf(bySection);
    }

    // ──────────────────────────────────────────────────────────────────
    // Load path (Doc 06 §3.1)
    // ──────────────────────────────────────────────────────────────────

    @Override
    public ConfigModel load() throws ConfigurationLoadException {
        reloadLock.lock();
        try {
            return loadLocked();
        } finally {
            reloadLock.unlock();
        }
    }

    private ConfigModel loadLocked() throws ConfigurationLoadException {
        PipelineOutcome outcome;
        try {
            outcome = runPipeline();
        } catch (PipelineAbortException e) {
            // Structural FATALs abort before any validation pass — no
            // validation event, no config_error events.
            throw loadException(e.issues());
        }

        // §3.6 startup error model over the completed validation pass.
        boolean fatal = outcome.issues().stream()
                .anyMatch(issue -> issue.severity() == Severity.FATAL);
        if (fatal) {
            publishValidationCompleted(outcome.documentVersion(), outcome.issues());
            publishConfigErrors(outcome.issues(), outcome.composedSchema());
            throw loadException(outcome.issues());
        }
        Map<String, Object> merged = outcome.merged();
        for (ConfigIssue issue : outcome.issues()) {
            if (issue.severity() == Severity.ERROR) {
                revertToDefault(merged, issue.path(), outcome.defaultsTree());
            }
        }

        // §3.7 step 7 (DP-11): persist the migrated document, refresh the
        // optimistic-concurrency token from the NEW file.
        Instant fileModifiedAt = outcome.fileModifiedAt();
        if (outcome.migrationRan()) {
            fileModifiedAt = writeBackMigratedDocument(
                    outcome.migratedDocument(), outcome.persistedVersion(),
                    fileModifiedAt);
        }

        // Stage 6: model construction + activation.
        Map<String, ConfigSection> sections =
                buildSections(merged, outcome.defaultsTree());
        ConfigModel model = new ConfigModel(
                outcome.documentVersion().major(),
                outcome.documentVersion().minor(),
                clock.instant(),
                fileModifiedAt,
                sections,
                merged);
        activeModel = model;

        writeSchemaCache();
        publishValidationCompleted(outcome.documentVersion(), outcome.issues());
        publishConfigErrors(outcome.issues(), outcome.composedSchema());
        log.info("Configuration loaded: schema={}.{} sections={} issues={}",
                model.configSchemaMajor(), model.configSchemaMinor(),
                sections.size(), outcome.issues().size());
        return model;
    }

    // ──────────────────────────────────────────────────────────────────
    // Reload path (Doc 06 §3.3 — M6.4)
    // ──────────────────────────────────────────────────────────────────

    @Override
    public ReloadResult reload() throws ConfigurationReloadException {
        reloadLock.lock();
        try {
            return reloadLocked();
        } finally {
            reloadLock.unlock();
        }
    }

    /**
     * The §3.3 pipeline body; the caller holds {@link #reloadLock}. The
     * write path invokes this directly under its already-held lock (DP-2).
     */
    private ReloadResult reloadLocked() throws ConfigurationReloadException {
        ConfigModel active = getCurrentModel();

        PipelineOutcome outcome;
        try {
            outcome = runPipeline();
        } catch (PipelineAbortException e) {
            throw reloadException(e.issues());
        }

        // §3.3/§3.6 atomicity rule (C5): FATAL or ERROR rejects the whole
        // candidate before any listener observes it and before any event.
        List<ConfigIssue> rejecting = outcome.issues().stream()
                .filter(issue -> issue.severity() != Severity.WARNING)
                .toList();
        if (!rejecting.isEmpty()) {
            throw reloadException(rejecting);
        }
        List<ConfigIssue> warnings = outcome.issues();

        ConfigModel candidate = new ConfigModel(
                outcome.documentVersion().major(),
                outcome.documentVersion().minor(),
                clock.instant(),
                outcome.fileModifiedAt(),
                buildSections(outcome.merged(), outcome.defaultsTree()),
                outcome.merged());

        // Diff (DP-5) then classify (DP-4) — synchronously, before swap
        // and publish (AMD-66-INV-02). A throwing listener rejects here,
        // with the active model untouched (AMD-66 §4).
        SchemaAnnotationIndex annotations =
                new SchemaAnnotationIndex(outcome.composedSchema());
        Map<String, List<ConfigChange>> changedSections =
                ConfigModelDiff.diff(active, candidate, annotations);
        Map<String, ReloadClassification> classifications =
                classifyChangedSections(active, candidate, changedSections);

        // Atomic swap (DP-1): one volatile reference assignment of an
        // immutable model — readers see wholly-old or wholly-new.
        activeModel = candidate;

        List<ConfigChange> allChanges = changedSections.values().stream()
                .flatMap(List::stream)
                .toList();
        ConfigChangeSet changeSet = new ConfigChangeSet(clock.instant(), allChanges);
        publishSectionReloaded(changedSections, classifications, warnings.size());
        log.info("Configuration reloaded: schema={}.{} changedSections={}"
                        + " changes={} warnings={}",
                candidate.configSchemaMajor(), candidate.configSchemaMinor(),
                changedSections.size(), allChanges.size(), warnings.size());
        return new ReloadResult(candidate, changeSet, warnings);
    }

    /**
     * Resolves each changed section's applied classification (DP-4): the
     * registered listener's return when one exists, else the most
     * restrictive per-property {@code x-reload} among the section's changed
     * keys (unannotated → {@code PROCESS_RESTART}, AMD-66 §2.3).
     */
    private Map<String, ReloadClassification> classifyChangedSections(
            ConfigModel active, ConfigModel candidate,
            Map<String, List<ConfigChange>> changedSections)
            throws ConfigurationReloadException {
        Map<String, ReloadClassification> classifications = new LinkedHashMap<>();
        for (Map.Entry<String, List<ConfigChange>> entry : changedSections.entrySet()) {
            String path = entry.getKey();
            ConfigurationChangeListener listener = listenersBySection.get(path);
            ReloadClassification classification;
            if (listener != null) {
                try {
                    classification = Objects.requireNonNull(
                            listener.onSectionChanged(
                                    sectionOrEmpty(active, path),
                                    sectionOrEmpty(candidate, path)),
                            "listener returned null classification");
                } catch (RuntimeException e) {
                    throw new ConfigurationReloadException(
                            "Configuration reload rejected; the active model is"
                                    + " unchanged: listener for section '" + path
                                    + "' failed classification", e);
                }
            } else {
                classification = mostRestrictive(entry.getValue());
            }
            classifications.put(path, classification);
        }
        return classifications;
    }

    /** A section absent from one model diffs and classifies as empty. */
    private static ConfigSection sectionOrEmpty(ConfigModel model, String path) {
        ConfigSection section = model.sections().get(path);
        return section != null ? section : new ConfigSection(path, Map.of(), Map.of());
    }

    private static ReloadClassification mostRestrictive(List<ConfigChange> changes) {
        ReloadClassification result = ReloadClassification.HOT;
        for (ConfigChange change : changes) {
            if (restrictiveness(change.reload()) > restrictiveness(result)) {
                result = change.reload();
            }
        }
        return result;
    }

    /** Explicit ranking — never {@code ordinal()} (the enum-order fragility rule). */
    private static int restrictiveness(ReloadClassification classification) {
        return switch (classification) {
            case HOT -> 0;
            case INTEGRATION_RESTART -> 1;
            case PROCESS_RESTART -> 2;
        };
    }

    // ──────────────────────────────────────────────────────────────────
    // Read surface
    // ──────────────────────────────────────────────────────────────────

    @Override
    public ConfigModel getCurrentModel() {
        ConfigModel model = activeModel;
        if (model == null) {
            throw new IllegalStateException(
                    "Configuration has not been loaded; ConfigurationService.load()"
                            + " runs first during startup (Doc 12)");
        }
        return model;
    }

    @Override
    public Optional<ConfigSection> getSection(String path) {
        Objects.requireNonNull(path, "path must not be null");
        return Optional.ofNullable(getCurrentModel().sections().get(path));
    }

    // ──────────────────────────────────────────────────────────────────
    // Write path (Doc 06 §3.5 — M6.4)
    // ──────────────────────────────────────────────────────────────────

    @Override
    public void write(List<ConfigMutation> mutations, Instant fileModifiedAt)
            throws ConfigurationValidationException, ConcurrentModificationException {
        Objects.requireNonNull(mutations, "mutations must not be null");
        Objects.requireNonNull(fileModifiedAt, "fileModifiedAt must not be null");
        reloadLock.lock();
        try {
            writeLocked(mutations, fileModifiedAt);
        } finally {
            reloadLock.unlock();
        }
    }

    private void writeLocked(List<ConfigMutation> mutations, Instant token)
            throws ConfigurationValidationException {
        ConfigModel active = getCurrentModel();
        Path rootFile = configDir.resolve(YamlLoader.ROOT_DOCUMENT_NAME);

        // §3.5 step 2 / §6.7: the caller's token must match the file's real
        // mtime (the zero-config absent-file token is the active model's
        // synthetic load-instant token, DP-5).
        Instant authoritative = Files.exists(rootFile)
                ? mtimeOf(rootFile)
                : active.fileModifiedAt();
        if (!authoritative.equals(token)) {
            throw new ConcurrentModificationException(
                    "Configuration file was modified externally: expected mtime "
                            + token + ", found " + authoritative
                            + "; reload and retry the write");
        }

        // §3.5 step 4: mutate the freshly re-parsed on-disk document — the
        // file is truth (INV-CE-01), and YamlLoader returns a fresh mutable
        // tree, so this IS the deep working copy (DP-6). The sparse user
        // document is preserved: schema defaults are never baked into the
        // file by a write.
        YamlLoader.Result parsed = new YamlLoader(configDir).load();
        if (!parsed.issues().isEmpty()) {
            throw new ConfigurationValidationException(
                    "Configuration write rejected: the on-disk document failed"
                            + " to parse: " + summarize(parsed.issues()));
        }
        Map<String, Object> mutated = parsed.document();
        for (ConfigMutation mutation : mutations) {
            applyMutation(mutated, mutation);
        }

        // §3.5 step 5: validate the mutated copy (default-merged, the same
        // §3.1 semantics). FATAL or ERROR → rejected, file untouched.
        String composedSchema = schemaRegistry.getComposedSchema();
        Map<String, Object> mergedCandidate =
                mergeDefaults(mutated, extractDefaults(composedSchema));
        List<ConfigIssue> rejecting =
                validator.validate(mergedCandidate, composedSchema).stream()
                        .filter(issue -> issue.severity() != Severity.WARNING)
                        .toList();
        if (!rejecting.isEmpty()) {
            throw new ConfigurationValidationException(
                    "Configuration write rejected: " + summarize(rejecting));
        }

        // §3.5 mitigation (REC-131): one-time backup before the first
        // programmatic write ever rewrites the hand-authored file.
        createFirstWriteBackupIfNeeded(rootFile);

        // §3.5 step 6: atomic flush — write-temp, fsync, rename (§6.8: a
        // failure at any step leaves the prior file intact).
        try {
            AtomicYamlWriter.writeAtomically(rootFile, AtomicYamlWriter.emit(mutated));
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Configuration write failed; the prior file is intact: "
                            + rootFile, e);
        }

        // §3.5 steps 7-8: reload from the new file under the held lock —
        // the token refresh and the config.section_reloaded publication
        // both ride the reload leg; the write publishes nothing itself
        // (DP-7, no double-publish).
        try {
            reloadLocked();
        } catch (ConfigurationReloadException e) {
            // The mutated document validated before the flush, so only a
            // listener rejection can land here. The file IS written; the
            // active model stays prior until the next accepted reload.
            throw new IllegalStateException(
                    "Configuration write flushed to disk but the post-write"
                            + " reload was rejected; the active model is"
                            + " unchanged until the next accepted reload", e);
        }
    }

    /**
     * Applies one mutation to the parsed document tree: a {@code null}
     * value removes the key (revert-to-default); otherwise intermediate
     * section maps are created as needed and the value is set.
     */
    private static void applyMutation(Map<String, Object> document,
                                      ConfigMutation mutation) {
        String[] segments = mutation.sectionPath().split("\\.", -1);
        if (mutation.newValue() == null) {
            Map<String, Object> node = document;
            for (String segment : segments) {
                Object next = node.get(segment);
                if (!(next instanceof Map<?, ?>)) {
                    return; // section absent — nothing to remove
                }
                node = asStringMap(next);
            }
            node.remove(mutation.key());
        } else {
            Map<String, Object> node = document;
            for (String segment : segments) {
                Object next = node.get(segment);
                if (next instanceof Map<?, ?>) {
                    node = asStringMap(next);
                } else {
                    // Absent (or scalar-squatted) segment: a mutation
                    // addresses a section, so an object node wins; the
                    // validation step judges the result.
                    Map<String, Object> created = new LinkedHashMap<>();
                    node.put(segment, created);
                    node = created;
                }
            }
            node.put(mutation.key(), mutation.newValue());
        }
    }

    /**
     * Creates {@code homesynapse.yaml.bak.{stamp}} before the first UI/API
     * write, with a WARNING (REC-131 — programmatic writes do not preserve
     * comments or formatting). "First" is a backup-file absence check; the
     * stamp comes from the injected clock. A backup failure fails the write
     * closed — losing the hand-authored original silently is worse than a
     * rejected write.
     */
    private void createFirstWriteBackupIfNeeded(Path rootFile) {
        if (!Files.exists(rootFile)) {
            return; // zero-config first write: nothing to preserve
        }
        try (Stream<Path> entries = Files.list(configDir)) {
            boolean backupExists = entries.anyMatch(path -> path.getFileName()
                    .toString().startsWith(WRITE_BACKUP_PREFIX));
            if (backupExists) {
                return;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Configuration write failed; the first-write backup check"
                            + " could not list " + configDir, e);
        }
        Path backup = configDir.resolve(
                WRITE_BACKUP_PREFIX + BACKUP_STAMP.format(clock.instant()));
        try {
            AtomicYamlWriter.copyBackup(rootFile, backup);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Configuration write failed; the first-write backup could"
                            + " not be created at " + backup, e);
        }
        log.warn("First UI/API configuration write: original {} preserved at"
                        + " {}; programmatic writes do not preserve comments or"
                        + " formatting (Doc 06 §3.5)",
                rootFile.getFileName(), backup.getFileName());
    }

    // ──────────────────────────────────────────────────────────────────
    // Shared pipeline (Doc 06 §3.1 stages 1-5)
    // ──────────────────────────────────────────────────────────────────

    /**
     * Everything the §3.1 stages produce, before either caller's error
     * model is applied.
     *
     * @param merged           post-migration, post-default-merge document
     *                         (the validation input; deeply fresh)
     * @param migratedDocument post-migration, PRE-merge document — the
     *                         §3.7 step-7 write-back payload (defaults are
     *                         never baked into the file)
     * @param defaultsTree     the schema-default tree from the composed
     *                         schema
     * @param persistedVersion the pair read from disk (pre-migration)
     * @param documentVersion  the effective pair (declared when migrated)
     * @param migrationRan     whether a migration chain was applied
     * @param fileModifiedAt   the root file's mtime (clock instant for the
     *                         absent-file zero-config case, DP-5)
     * @param composedSchema   the composed schema JSON used for validation
     * @param issues           the validation pass's findings
     */
    private record PipelineOutcome(
            Map<String, Object> merged,
            Map<String, Object> migratedDocument,
            Map<String, Object> defaultsTree,
            SchemaVersion persistedVersion,
            SchemaVersion documentVersion,
            boolean migrationRan,
            Instant fileModifiedAt,
            String composedSchema,
            List<ConfigIssue> issues) {
    }

    /**
     * Pre-validation structural abort (parse failure, malformed or
     * unreachable schema version). The caller maps it to its pipeline's
     * exception type — {@link ConfigurationLoadException} on load,
     * {@link ConfigurationReloadException} on reload.
     */
    private static final class PipelineAbortException extends RuntimeException {

        @java.io.Serial
        private static final long serialVersionUID = 1L;

        private final transient List<ConfigIssue> issues;

        PipelineAbortException(List<ConfigIssue> issues) {
            super("configuration pipeline aborted: "
                    + issues.size() + " structural issue(s)");
            this.issues = List.copyOf(issues);
        }

        List<ConfigIssue> issues() {
            return issues;
        }
    }

    /**
     * Runs §3.1 stages 1-5: parse (via {@link YamlLoader} — the single
     * {@code LoadSettings} construction point, so the reload re-parse
     * carries the YAML 1.2 Core schema by construction, DP-12), the AMD-67
     * migration chain, the default merge, and allErrors validation.
     */
    private PipelineOutcome runPipeline() {
        Path rootFile = configDir.resolve(YamlLoader.ROOT_DOCUMENT_NAME);
        Instant fileModifiedAt = readFileModifiedAt(rootFile);

        YamlLoader.Result parsed = new YamlLoader(configDir).load();
        if (!parsed.issues().isEmpty()) {
            throw new PipelineAbortException(parsed.issues());
        }

        Map<String, Object> workingMap = parsed.document();
        SchemaVersion persisted = readSchemaVersion(workingMap);
        SchemaVersion documentVersion = persisted;
        boolean migrationRan = false;
        if (persisted.major() < declaredSchemaMajor) {
            workingMap = migrate(workingMap, persisted);
            documentVersion =
                    new SchemaVersion(declaredSchemaMajor, declaredSchemaMinor);
            migrationRan = true;
        }

        String composedSchema = schemaRegistry.getComposedSchema();
        Map<String, Object> defaultsTree = extractDefaults(composedSchema);
        Map<String, Object> merged = mergeDefaults(workingMap, defaultsTree);

        List<ConfigIssue> issues = validator.validate(merged, composedSchema);
        for (ConfigIssue issue : issues) {
            log.warn("Configuration issue [{}] at '{}': {}",
                    issue.severity(), issue.path(), issue.message());
        }
        return new PipelineOutcome(merged, workingMap, defaultsTree, persisted,
                documentVersion, migrationRan, fileModifiedAt, composedSchema,
                issues);
    }

    // ──────────────────────────────────────────────────────────────────
    // Schema version + migration (AMD-67, Doc 06 §3.7)
    // ──────────────────────────────────────────────────────────────────

    private record SchemaVersion(int major, int minor) {
    }

    /**
     * Reads the DP-1 object-form {@code schema_version: { major: N, minor: M }}
     * top-level key. An absent key is treated as current: zero-config and
     * minimal documents carry no version key, take the declared pair, and
     * never migrate (INV-CE-02).
     */
    private SchemaVersion readSchemaVersion(Map<String, Object> document) {
        Object raw = document.get(SCHEMA_VERSION_KEY);
        if (raw == null) {
            return new SchemaVersion(declaredSchemaMajor, declaredSchemaMinor);
        }
        if (!(raw instanceof Map<?, ?> pair)
                || !(pair.get("major") instanceof Integer major)
                || !(pair.get("minor") instanceof Integer minor)) {
            throw new PipelineAbortException(List.of(fatalIssue(SCHEMA_VERSION_KEY,
                    "schema_version must use the object form"
                            + " 'schema_version: { major: N, minor: M }': " + raw, raw)));
        }
        if (major < 1 || minor < 0) {
            throw new PipelineAbortException(List.of(fatalIssue(SCHEMA_VERSION_KEY,
                    "schema_version requires major >= 1 and minor >= 0:"
                            + " major=" + major + ", minor=" + minor, raw)));
        }
        if (major > declaredSchemaMajor) {
            throw new PipelineAbortException(List.of(fatalIssue(SCHEMA_VERSION_KEY,
                    "schema_version major " + major + " is newer than the declared major "
                            + declaredSchemaMajor + "; migration is forward-only (AMD-67)",
                    raw)));
        }
        return new SchemaVersion(major, minor);
    }

    /**
     * Selects and applies the migration chain, then stamps the document with
     * the declared pair (§3.7 step 5). Selection is the
     * {@code ConfigMigratorChainTest} reference semantics: every migrator
     * with {@code fromMajor} in {@code [persistedMajor, declaredMajor)},
     * ordered by {@code (fromMajor, fromMinor)}.
     */
    private Map<String, Object> migrate(Map<String, Object> document,
                                        SchemaVersion persisted) {
        List<ConfigMigrator> chain = migrators.stream()
                .filter(m -> m.fromMajor() >= persisted.major()
                        && m.fromMajor() < declaredSchemaMajor)
                .sorted(Comparator.comparingInt(ConfigMigrator::fromMajor)
                        .thenComparingInt(ConfigMigrator::fromMinor))
                .toList();
        if (chain.isEmpty()
                || chain.get(chain.size() - 1).toMajor() != declaredSchemaMajor) {
            throw new PipelineAbortException(List.of(fatalIssue(SCHEMA_VERSION_KEY,
                    "no migration chain reaches schema major " + declaredSchemaMajor
                            + " from persisted major " + persisted.major()
                            + " (registered migrators: " + migrators.size() + ")", null)));
        }
        Map<String, Object> current = document;
        for (ConfigMigrator migrator : chain) {
            log.info("Migrating configuration schema {}.{} -> {}.{}",
                    migrator.fromMajor(), migrator.fromMinor(),
                    migrator.toMajor(), migrator.toMinor());
            current = migrator.migrate(current).migratedConfig();
        }
        Map<String, Object> migrated = new LinkedHashMap<>(current);
        Map<String, Object> stampedVersion = new LinkedHashMap<>();
        stampedVersion.put("major", declaredSchemaMajor);
        stampedVersion.put("minor", declaredSchemaMinor);
        migrated.put(SCHEMA_VERSION_KEY, stampedVersion);
        return migrated;
    }

    /**
     * §3.7 step 7 (DP-11): copy the original to the never-auto-deleted
     * pre-migration backup, atomically replace the file with the migrated
     * document, and return the NEW file's mtime as the refreshed token. A
     * failure anywhere leaves the original (plus the backup when it was
     * already created) intact — recoverable, never torn — and the load
     * continues on the in-memory migration: the idempotent chain re-runs on
     * the next boot.
     */
    private Instant writeBackMigratedDocument(Map<String, Object> migratedDocument,
                                              SchemaVersion persisted,
                                              Instant currentToken) {
        Path rootFile = configDir.resolve(YamlLoader.ROOT_DOCUMENT_NAME);
        Path backup = configDir.resolve(PRE_MIGRATION_BACKUP_PREFIX
                + persisted.major() + "." + persisted.minor());
        try {
            AtomicYamlWriter.copyBackup(rootFile, backup);
            AtomicYamlWriter.writeAtomically(rootFile,
                    AtomicYamlWriter.emit(migratedDocument));
            log.info("Migrated configuration persisted at schema {}.{};"
                            + " the original is retained at {}",
                    declaredSchemaMajor, declaredSchemaMinor, backup.getFileName());
            return mtimeOf(rootFile);
        } catch (IOException | UncheckedIOException e) {
            log.warn("Migration write-back failed; the on-disk document remains"
                            + " at schema {}.{} and the idempotent chain re-runs"
                            + " on the next load", persisted.major(), persisted.minor(),
                    e);
            return currentToken;
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Default merge (Doc 06 §3.1 stage 4)
    // ──────────────────────────────────────────────────────────────────

    /**
     * Extracts the {@code default}-annotated tree from the composed schema.
     * A property with an explicit default contributes it; a property with
     * only nested defaults contributes the nested map.
     */
    private static Map<String, Object> extractDefaults(String composedSchemaJson) {
        JsonNode schemaNode;
        try {
            schemaNode = MAPPER.readTree(composedSchemaJson);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "composed schema is not valid JSON: " + e.getOriginalMessage(), e);
        }
        return defaultsOf(schemaNode);
    }

    private static Map<String, Object> defaultsOf(JsonNode schemaNode) {
        Map<String, Object> defaults = new LinkedHashMap<>();
        JsonNode properties = schemaNode.get("properties");
        if (properties == null || !properties.isObject()) {
            return defaults;
        }
        for (Iterator<String> names = properties.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            JsonNode propertySchema = properties.get(name);
            JsonNode defaultNode = propertySchema.get("default");
            if (defaultNode != null) {
                defaults.put(name, MAPPER.convertValue(defaultNode, Object.class));
            } else {
                Map<String, Object> nested = defaultsOf(propertySchema);
                if (!nested.isEmpty()) {
                    defaults.put(name, nested);
                }
            }
        }
        return defaults;
    }

    /**
     * Merges schema defaults into the document for absent keys only — user
     * values always win. Returns a deeply fresh tree so the §3.6 ERROR
     * reverts can mutate it without aliasing the defaults tree.
     */
    private static Map<String, Object> mergeDefaults(Map<String, Object> user,
                                                     Map<String, Object> defaults) {
        Map<String, Object> merged = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : user.entrySet()) {
            Object userValue = entry.getValue();
            Object defaultValue = defaults.get(entry.getKey());
            if (userValue instanceof Map<?, ?>) {
                Map<String, Object> nestedDefaults =
                        defaultValue instanceof Map<?, ?> ? asStringMap(defaultValue) : Map.of();
                merged.put(entry.getKey(),
                        mergeDefaults(asStringMap(userValue), nestedDefaults));
            } else {
                merged.put(entry.getKey(), userValue);
            }
        }
        for (Map.Entry<String, Object> entry : defaults.entrySet()) {
            if (merged.containsKey(entry.getKey())) {
                continue;
            }
            Object defaultValue = entry.getValue();
            merged.put(entry.getKey(), defaultValue instanceof Map<?, ?>
                    ? mergeDefaults(Map.of(), asStringMap(defaultValue))
                    : defaultValue);
        }
        return merged;
    }

    /** Reverts an ERROR key to its schema default, or removes it when none. */
    private static void revertToDefault(Map<String, Object> merged, String dottedPath,
                                        Map<String, Object> defaultsTree) {
        List<String> segments = List.of(dottedPath.split("\\.", -1));
        Map<String, Object> parent = merged;
        for (int i = 0; i < segments.size() - 1; i++) {
            Object next = parent.get(segments.get(i));
            if (!(next instanceof Map<?, ?>)) {
                return;
            }
            parent = asStringMap(next);
        }
        String leaf = segments.get(segments.size() - 1);
        Object defaultValue = valueAt(defaultsTree, segments);
        if (defaultValue != null) {
            parent.put(leaf, defaultValue);
        } else {
            parent.remove(leaf);
        }
    }

    private static Object valueAt(Map<String, Object> tree, List<String> segments) {
        Object current = tree;
        for (String segment : segments) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(segment);
        }
        return current;
    }

    // ──────────────────────────────────────────────────────────────────
    // Model construction (Doc 06 §3.1 stage 6)
    // ──────────────────────────────────────────────────────────────────

    /**
     * Flattens every nested map node into a dotted-path {@link ConfigSection}
     * ({@code "persistence"}, {@code "persistence.retention"},
     * {@code "integrations.zigbee"}, …). The top-level
     * {@code schema_version} key is pipeline metadata, carried on the model
     * as the typed pair, not a section.
     */
    private static Map<String, ConfigSection> buildSections(
            Map<String, Object> merged, Map<String, Object> defaultsTree) {
        Map<String, ConfigSection> sections = new LinkedHashMap<>();
        collectSections("", merged, defaultsTree, sections);
        return sections;
    }

    private static void collectSections(String parentPath, Map<String, Object> node,
                                        Map<String, Object> defaultsNode,
                                        Map<String, ConfigSection> out) {
        for (Map.Entry<String, Object> entry : node.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?>)) {
                continue;
            }
            String path = parentPath.isEmpty()
                    ? entry.getKey()
                    : parentPath + "." + entry.getKey();
            if (SCHEMA_VERSION_KEY.equals(path)) {
                continue;
            }
            Map<String, Object> values = asStringMap(entry.getValue());
            Object rawDefaults = defaultsNode.get(entry.getKey());
            Map<String, Object> sectionDefaults = rawDefaults instanceof Map<?, ?>
                    ? asStringMap(rawDefaults)
                    : Map.of();
            out.put(path, new ConfigSection(path, values, sectionDefaults));
            collectSections(path, values, sectionDefaults, out);
        }
    }

    /**
     * Narrow unchecked cast for tree nodes this pipeline built itself —
     * every map in the document tree is string-keyed by
     * {@code YamlLoader}'s normalization.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asStringMap(Object value) {
        return (Map<String, Object>) value;
    }

    // ──────────────────────────────────────────────────────────────────
    // Observability (AMD-70; DP-9/DP-10 ruled metadata)
    // ──────────────────────────────────────────────────────────────────

    private void publishValidationCompleted(SchemaVersion version,
                                            List<ConfigIssue> issues) {
        Map<String, Integer> severityCounts = new LinkedHashMap<>();
        for (ConfigIssue issue : issues) {
            severityCounts.merge(issue.severity().name(), 1, Integer::sum);
        }
        publishObservability(EventTypes.CONFIG_VALIDATION_COMPLETED,
                new ConfigValidationCompletedEvent(
                        version.major(), version.minor(), issues.size(),
                        severityCounts),
                "configuration load continues");
    }

    /**
     * DP-10 (R1): one {@code config_error} per ERROR-severity issue after a
     * completed startup validation pass (Doc 06 §4.5) — including a FATAL
     * pass, before its throw. The §12.4 fence redacts {@code x-sensitive}
     * paths: the message becomes {@code "[REDACTED]"} and the default
     * {@code "(none)"} — the invalid value itself is never published.
     */
    private void publishConfigErrors(List<ConfigIssue> issues, String composedSchema) {
        List<ConfigIssue> errors = issues.stream()
                .filter(issue -> issue.severity() == Severity.ERROR)
                .toList();
        if (errors.isEmpty()) {
            return;
        }
        SchemaAnnotationIndex annotations = new SchemaAnnotationIndex(composedSchema);
        for (ConfigIssue issue : errors) {
            boolean sensitive = annotations.sensitiveAt(issue.path());
            String message = sensitive ? REDACTED_MESSAGE : issue.message();
            String appliedDefault = !sensitive && issue.appliedDefault() != null
                    ? String.valueOf(issue.appliedDefault())
                    : NO_DEFAULT;
            publishObservability(EventTypes.CONFIG_ERROR,
                    new ConfigErrorEvent(issue.path(), issue.severity().name(),
                            message, appliedDefault),
                    "configuration load continues");
        }
    }

    /**
     * AMD-70 §4: one {@code config.section_reloaded} per changed section,
     * after listener classification, on the reload's own thread. The
     * payload carries names, counts, and the classification — never values
     * (§12.4; DP-7).
     */
    private void publishSectionReloaded(
            Map<String, List<ConfigChange>> changedSections,
            Map<String, ReloadClassification> classifications,
            int warningCount) {
        for (Map.Entry<String, List<ConfigChange>> entry : changedSections.entrySet()) {
            publishObservability(EventTypes.CONFIG_SECTION_RELOADED,
                    new ConfigSectionReloadedEvent(
                            entry.getKey(),
                            entry.getValue().size(),
                            warningCount,
                            classifications.get(entry.getKey()).name()),
                    "the reload is already applied");
        }
    }

    /**
     * Publishes one observability event with the ruled metadata (DP-9):
     * DIAGNOSTIC, SYSTEM origin, null eventTime, system subject, null
     * actor, via publishRoot. Publish failure is logged and never fails
     * the surrounding operation (AMD-70-INV-01) — the configuration file,
     * not the event log, is the source of truth.
     */
    private void publishObservability(String eventType, DomainEvent payload,
                                      String continuation) {
        EventDraft draft = new EventDraft(
                eventType,
                1,
                null,
                SubjectRef.system(systemId),
                EventPriority.DIAGNOSTIC,
                EventOrigin.SYSTEM,
                payload,
                null,
                null);
        try {
            eventPublisher.publishRoot(draft);
        } catch (SequenceConflictException | RuntimeException e) {
            log.error("{} publication failed; {}", eventType, continuation, e);
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Filesystem plumbing
    // ──────────────────────────────────────────────────────────────────

    private Instant readFileModifiedAt(Path rootFile) {
        if (Files.exists(rootFile)) {
            try {
                return Files.getLastModifiedTime(rootFile).toInstant();
            } catch (IOException e) {
                log.warn("Cannot stat {}; using the load instant as the"
                        + " concurrency token", rootFile, e);
            }
        }
        // Absent file (zero-config): no mtime exists, so the write path's
        // optimistic concurrency token is the load instant (DP-5).
        return clock.instant();
    }

    private static Instant mtimeOf(Path file) {
        try {
            return Files.getLastModifiedTime(file).toInstant();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot stat " + file, e);
        }
    }

    private void writeSchemaCache() {
        Path cacheFile = configDir
                .resolve(SCHEMAS_CACHE_DIRECTORY)
                .resolve(SCHEMA_CACHE_FILE_NAME);
        try {
            schemaRegistry.writeComposedSchema(cacheFile);
        } catch (IOException e) {
            // The schemas/ directory is a regenerable cache, never the
            // authoritative schema source (AMD-71 §2.4) — failing to write it
            // degrades IDE tooling, not the load.
            log.warn("Composed-schema cache write failed: {}", cacheFile, e);
        }
    }

    private static ConfigIssue fatalIssue(String path, String message,
                                          Object invalidValue) {
        return new ConfigIssue(Severity.FATAL, path, message, invalidValue, null, null);
    }

    private static String summarize(List<ConfigIssue> issues) {
        return issues.stream()
                .map(issue -> issue.path() + ": " + issue.message())
                .collect(Collectors.joining("; "));
    }

    private static ConfigurationLoadException loadException(List<ConfigIssue> issues) {
        List<ConfigIssue> fatals = issues.stream()
                .filter(issue -> issue.severity() == Severity.FATAL)
                .toList();
        return new ConfigurationLoadException(
                "Configuration load failed with FATAL issues: " + summarize(fatals));
    }

    private static ConfigurationReloadException reloadException(
            List<ConfigIssue> issues) {
        List<ConfigIssue> rejecting = issues.stream()
                .filter(issue -> issue.severity() != Severity.WARNING)
                .toList();
        return new ConfigurationReloadException(
                "Configuration reload rejected; the active model is unchanged: "
                        + summarize(rejecting));
    }
}
