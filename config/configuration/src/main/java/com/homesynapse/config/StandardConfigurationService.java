/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homesynapse.event.ConfigValidationCompletedEvent;
import com.homesynapse.event.ConfigurationValidationException;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * {@link ConfigurationService} implementation — the M6.1 load path of the
 * Doc 06 §3.1 pipeline: read and parse the AMD-71 layout (via
 * {@link YamlLoader}), run the AMD-67 migration chain on a major mismatch,
 * merge JSON Schema defaults, validate the merged whole against the
 * composed schema (AMD-71 §2.4 compose-after-merge), apply the §3.6
 * startup error model, construct the immutable {@link ConfigModel}, and
 * publish {@code config.validation_completed} (AMD-70).
 *
 * <h2>Startup error model (DP-2 / Doc 06 §3.6)</h2>
 *
 * <p>On {@link #load()}: {@link Severity#ERROR} issues revert the offending
 * key to its schema default — the system starts degraded but functional
 * (INV-RF-06). {@link Severity#FATAL} issues abort with
 * {@link ConfigurationLoadException}. Structural FATALs from the parse
 * stage abort before any validation pass runs.</p>

 * <h2>Migration trigger (AMD-67-INV-02)</h2>
 *
 * <p>Only a persisted {@code configSchemaMajor} below the declared major
 * triggers the migrator chain; a minor-only mismatch never migrates. The
 * chain selects every registered migrator whose {@code fromMajor} lies in
 * {@code [persistedMajor, declaredMajor)}, ordered by
 * {@code (fromMajor, fromMinor)} — the reference semantics pinned by
 * {@code ConfigMigratorChainTest}. A persisted major NEWER than the
 * declared major is FATAL (migration is forward-only, §3.7). Migration is
 * applied in memory; persisting the migrated document back to disk rides
 * the M6.4 atomic write path (AMD-67 §6 fences migration execution to the
 * chain contract).</p>
 *
 * <h2>Observability (AMD-70, ruled metadata 2026-06-10)</h2>
 *
 * <p>{@code config.validation_completed} fires exactly once per completed
 * validation pass — including a pass that found FATAL issues — with
 * DIAGNOSTIC priority, {@link EventOrigin#SYSTEM}, {@code null}
 * {@code eventTime} (derived/observability events never use wall-clock
 * event time), the system subject, and a {@code null} actor, via
 * {@link EventPublisher#publishRoot}. The event is observability-only
 * (AMD-70-INV-01): a publish failure is logged and never fails the
 * load.</p>
 *
 * <h2>Listener registration (AMD-66 §2.4, ruled List form)</h2>
 *
 * <p>{@link ConfigurationChangeListener}s are registered at construction
 * from a {@code List}; this service builds the internal map keyed by
 * {@link ConfigurationChangeListener#sectionPath()} and rejects a
 * duplicate section path with {@link IllegalArgumentException}. Listeners
 * are <em>invoked</em> by the M6.4 reload pipeline; M6.1 only registers
 * them.</p>
 *
 * <h2>Staged methods</h2>
 *
 * <p>{@link #reload()} (§3.3) and {@link #write(List, Instant)} (§3.5) are
 * the M6.4 hot-reload milestone and throw
 * {@link UnsupportedOperationException} until it lands.</p>
 *
 * <h2>Thread safety</h2>
 *
 * <p>{@link #getCurrentModel()} and {@link #getSection(String)} are
 * non-blocking volatile reads. {@link #load()} is expected to run once
 * during startup (Doc 12); concurrent loads are not serialized in M6.1 —
 * the reload lock arrives with the M6.4 write/reload paths.</p>
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
     * frozen at construction; consumed by the M6.4 reload pipeline.
     */
    private final Map<String, ConfigurationChangeListener> listenersBySection;

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
     * @param clock               time source for {@code loadedAt} and the
     *                            absent-file {@code fileModifiedAt} fallback
     *                            (DP-5, §4c — never the ambient clock);
     *                            never {@code null}
     * @param systemId            this installation's system identity — the
     *                            subject of configuration observability
     *                            events; never {@code null}
     * @param eventPublisher      publisher for
     *                            {@code config.validation_completed};
     *                            never {@code null}
     * @param schemaRegistry      the composed-schema source (AMD-71 §2.4);
     *                            never {@code null}
     * @param validator           the allErrors schema validator;
     *                            never {@code null}
     * @param migrators           registered forward-only migrators; may be
     *                            empty (no production migrator exists at
     *                            M6.1); never {@code null}
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
        Path rootFile = configDir.resolve(YamlLoader.ROOT_DOCUMENT_NAME);
        Instant fileModifiedAt = readFileModifiedAt(rootFile);

        // Stages 1-2: read + parse + include splice. Structural FATALs abort
        // before any validation pass — no validation event is published.
        YamlLoader.Result parsed = new YamlLoader(configDir).load();
        if (!parsed.issues().isEmpty()) {
            throw loadException(parsed.issues());
        }

        // §3.7: read the persisted pair, migrate on a major mismatch.
        Map<String, Object> workingMap = parsed.document();
        SchemaVersion persisted = readSchemaVersion(workingMap);
        SchemaVersion documentVersion = persisted;
        if (persisted.major() < declaredSchemaMajor) {
            workingMap = migrate(workingMap, persisted);
            documentVersion =
                    new SchemaVersion(declaredSchemaMajor, declaredSchemaMinor);
        }

        // Stage 4 + AMD-71 §2.4: compose AFTER the includes are merged, then
        // merge schema defaults into the document (INV-CE-02 — an empty
        // document becomes a complete configuration here).
        String composedSchema = schemaRegistry.getComposedSchema();
        Map<String, Object> defaultsTree = extractDefaults(composedSchema);
        Map<String, Object> merged = mergeDefaults(workingMap, defaultsTree);

        // Stage 5: allErrors validation over the merged whole, then the
        // §3.6 startup error model.
        List<ConfigIssue> issues = validator.validate(merged, composedSchema);
        for (ConfigIssue issue : issues) {
            log.warn("Configuration issue [{}] at '{}': {}",
                    issue.severity(), issue.path(), issue.message());
        }
        boolean fatal = issues.stream()
                .anyMatch(issue -> issue.severity() == Severity.FATAL);
        if (fatal) {
            publishValidationCompleted(documentVersion, issues);
            throw loadException(issues);
        }
        for (ConfigIssue issue : issues) {
            if (issue.severity() == Severity.ERROR) {
                revertToDefault(merged, issue.path(), defaultsTree);
            }
        }

        // Stage 6: model construction.
        Map<String, ConfigSection> sections = buildSections(merged, defaultsTree);
        ConfigModel model = new ConfigModel(
                documentVersion.major(),
                documentVersion.minor(),
                clock.instant(),
                fileModifiedAt,
                sections,
                merged);
        activeModel = model;

        writeSchemaCache();
        publishValidationCompleted(documentVersion, issues);
        log.info("Configuration loaded: schema={}.{} sections={} issues={}",
                model.configSchemaMajor(), model.configSchemaMinor(),
                sections.size(), issues.size());
        return model;
    }

    @Override
    public ReloadResult reload() throws ConfigurationReloadException {
        throw new UnsupportedOperationException(
                "Configuration reload is not available until the M6.4 hot-reload"
                        + " milestone; only load() is implemented in M6.1");
    }

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

    @Override
    public void write(List<ConfigMutation> mutations, Instant fileModifiedAt)
            throws ConfigurationValidationException, ConcurrentModificationException {
        throw new UnsupportedOperationException(
                "Configuration write path is not available until the M6.4"
                        + " hot-reload milestone; only load() is implemented in M6.1");
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
    private SchemaVersion readSchemaVersion(Map<String, Object> document)
            throws ConfigurationLoadException {
        Object raw = document.get(SCHEMA_VERSION_KEY);
        if (raw == null) {
            return new SchemaVersion(declaredSchemaMajor, declaredSchemaMinor);
        }
        if (!(raw instanceof Map<?, ?> pair)
                || !(pair.get("major") instanceof Integer major)
                || !(pair.get("minor") instanceof Integer minor)) {
            throw loadException(List.of(fatalIssue(SCHEMA_VERSION_KEY,
                    "schema_version must use the object form"
                            + " 'schema_version: { major: N, minor: M }': " + raw, raw)));
        }
        if (major < 1 || minor < 0) {
            throw loadException(List.of(fatalIssue(SCHEMA_VERSION_KEY,
                    "schema_version requires major >= 1 and minor >= 0:"
                            + " major=" + major + ", minor=" + minor, raw)));
        }
        if (major > declaredSchemaMajor) {
            throw loadException(List.of(fatalIssue(SCHEMA_VERSION_KEY,
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
                                        SchemaVersion persisted)
            throws ConfigurationLoadException {
        List<ConfigMigrator> chain = migrators.stream()
                .filter(m -> m.fromMajor() >= persisted.major()
                        && m.fromMajor() < declaredSchemaMajor)
                .sorted(Comparator.comparingInt(ConfigMigrator::fromMajor)
                        .thenComparingInt(ConfigMigrator::fromMinor))
                .toList();
        if (chain.isEmpty()
                || chain.get(chain.size() - 1).toMajor() != declaredSchemaMajor) {
            throw loadException(List.of(fatalIssue(SCHEMA_VERSION_KEY,
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
    // Observability (AMD-70)
    // ──────────────────────────────────────────────────────────────────

    private void publishValidationCompleted(SchemaVersion version,
                                            List<ConfigIssue> issues) {
        Map<String, Integer> severityCounts = new LinkedHashMap<>();
        for (ConfigIssue issue : issues) {
            severityCounts.merge(issue.severity().name(), 1, Integer::sum);
        }
        ConfigValidationCompletedEvent payload = new ConfigValidationCompletedEvent(
                version.major(), version.minor(), issues.size(), severityCounts);
        EventDraft draft = new EventDraft(
                EventTypes.CONFIG_VALIDATION_COMPLETED,
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
            // Observability-only (AMD-70-INV-01): the configuration file, not
            // the event log, is the source of truth — a publish failure must
            // not fail the load.
            log.error("config.validation_completed publication failed;"
                    + " configuration load continues", e);
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

    private static ConfigurationLoadException loadException(List<ConfigIssue> issues) {
        String detail = issues.stream()
                .filter(issue -> issue.severity() == Severity.FATAL)
                .map(issue -> issue.path() + ": " + issue.message())
                .collect(Collectors.joining("; "));
        return new ConfigurationLoadException(
                "Configuration load failed with FATAL issues: " + detail);
    }
}
