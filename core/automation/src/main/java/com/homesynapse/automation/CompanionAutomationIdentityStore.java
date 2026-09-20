/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;

import com.homesynapse.platform.identity.AutomationId;
import com.homesynapse.platform.identity.Ulid;
import com.homesynapse.platform.identity.UlidFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The DURABLE {@link AutomationIdentityStore}: automation and generated-trigger identity
 * kept in the engine-managed companion {@code automations.ids.yaml} (Doc 07 §4.1;
 * AMD-93 §2.3), so an automation keeps its {@link AutomationId} — and the run history
 * the log holds under it — across restarts (AUTO-ID-1; MEASURE-2b F-1).
 *
 * <p><strong>Policy here, bytes elsewhere.</strong> This class owns the document's shape,
 * minting, retention, fail-closed validation and the write-once-if-changed rule; the
 * bytes cross the {@link AutomationIdentityCompanion} seam, so this module reads no file
 * and carries no YAML library. The document:</p>
 * <pre>
 * schema_version: 1
 * automations:
 *   &lt;slug&gt;:                     (slugs sorted)
 *     id: &lt;ULID&gt;
 *     last_seen: &lt;ISO-8601 Z, whole seconds, from the injected clock&gt;
 *     triggers:                  (generated trigger ids, numeric index order)
 *       "&lt;index&gt;": &lt;ULID&gt;
 * </pre>
 *
 * <p><strong>The bracket is the contract.</strong> Every
 * {@link AutomationDefinitionLoader#load} over this store runs between
 * {@link #beginLoad()} and {@link #endLoad()}; an identity asked for outside the bracket
 * throws {@link IllegalStateException} — a load that forgot the bracket fails loudly
 * instead of minting identities nothing will ever persist.</p>
 *
 * <p><strong>Fail closed.</strong> A companion that exists and cannot be read, or whose
 * document is not exactly the shape above ({@code schema_version} other than 1, an
 * unknown key, a value that is not what this class wrote, one id under two slugs), fails
 * {@link #beginLoad()} with an {@link IllegalStateException} naming the companion.
 * Identities are never re-minted over a companion that exists: a re-mint silently orphans
 * every run on record. An absent companion is the first boot.</p>
 *
 * <p><strong>Retention.</strong> {@link #endLoad()} stamps {@code last_seen} on every
 * slug the load named, then retires each entry whose {@code last_seen} is older than
 * {@link #RETENTION} — a definition removed and re-added inside the window keeps its id
 * (Doc 07 §4.1). A retired id is never handed out again: a later mint for the slug is a
 * new ULID ({@code UlidFactory} is monotonic — LTD-04).</p>
 *
 * <p><strong>Write once, if changed.</strong> {@link #endLoad()} replaces the companion
 * exactly once, and only when the document changed — a mint, a retirement, or a
 * {@code last_seen} that moved. A write that fails is logged at ERROR and does NOT throw:
 * the load's identities stay valid for this process, the store stays dirty, and the next
 * {@link #endLoad()} retries; the next process re-mints only what was never persisted.</p>
 *
 * <p>Thread-safe via a {@link ReentrantLock} (LTD-11 — never {@code synchronized}):
 * identities may be asked for from any thread inside a load. The bracket itself is one
 * load at a time, run by one thread (the composition root's boot thread); the lock is
 * never held across the companion's I/O. Time comes from the injected {@link Clock}
 * (NO_DIRECT_TIME_ACCESS-safe).</p>
 */
public final class CompanionAutomationIdentityStore implements AutomationIdentityStore {

    private static final Logger LOG =
            LoggerFactory.getLogger(CompanionAutomationIdentityStore.class);

    /**
     * How long an entry no load has named is kept (Doc 07 §4.1). A constant in this
     * work unit; the design's {@code automation.identity_retention_days} is the future
     * knob.
     */
    static final Duration RETENTION = Duration.ofDays(30);

    /** The only document version this class reads or writes. */
    static final int SCHEMA_VERSION = 1;

    private static final String KEY_SCHEMA_VERSION = "schema_version";
    private static final String KEY_AUTOMATIONS = "automations";
    private static final String KEY_ID = "id";
    private static final String KEY_LAST_SEEN = "last_seen";
    private static final String KEY_TRIGGERS = "triggers";
    private static final Set<String> DOCUMENT_KEYS = Set.of(KEY_SCHEMA_VERSION, KEY_AUTOMATIONS);
    private static final Set<String> ENTRY_KEYS = Set.of(KEY_ID, KEY_LAST_SEEN, KEY_TRIGGERS);

    private final AutomationIdentityCompanion companion;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();

    // ── guarded by lock ─────────────────────────────────────────────────────
    private final TreeMap<String, Entry> entries = new TreeMap<>();
    private final Set<String> touched = new HashSet<>();
    private boolean companionRead;
    private boolean loading;
    /** Bumped by every change to the document; the companion holds {@link #persistedRevision}. */
    private long revision;
    private long persistedRevision;
    private int mintedThisLoad;
    private int triggersMintedThisLoad;

    /**
     * Constructs a store over a companion. Nothing is read until {@link #beginLoad()}.
     *
     * @param companion the I/O seam behind the companion document, never {@code null}
     * @param clock     the injected clock — mints ULIDs, stamps {@code last_seen}, and
     *                  measures retention; never {@code null}
     */
    public CompanionAutomationIdentityStore(AutomationIdentityCompanion companion, Clock clock) {
        this.companion = Objects.requireNonNull(companion, "companion");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Opens a load. The FIRST call reads the companion (absent → the first boot); later
     * calls in the same process read nothing — this store is the companion's only writer,
     * and its memory is never replaced by an older document after a failed write.
     *
     * @throws IllegalStateException if the companion exists and cannot be read, or its
     *         document is malformed — the message names the companion. Nothing is minted
     *         and nothing is written; the caller fails closed.
     */
    public void beginLoad() {
        boolean read;
        lock.lock();
        try {
            read = !companionRead;
        } finally {
            lock.unlock();
        }

        TreeMap<String, Entry> fromCompanion = null;
        boolean firstBoot = false;
        if (read) {
            Optional<Map<String, Object>> document = companion.read();
            firstBoot = document.isEmpty();
            fromCompanion = document.isPresent() ? parse(document.get()) : new TreeMap<>();
        }

        lock.lock();
        try {
            if (fromCompanion != null && !companionRead) {
                entries.putAll(fromCompanion);
                companionRead = true;
            }
            touched.clear();
            mintedThisLoad = 0;
            triggersMintedThisLoad = 0;
            loading = true;
        } finally {
            lock.unlock();
        }
        if (fromCompanion != null) {
            LOG.info("automation.identity_loaded: path={} automations={} triggers={} first_boot={}",
                    companion, fromCompanion.size(),
                    fromCompanion.values().stream().mapToInt(entry -> entry.triggers.size()).sum(),
                    firstBoot);
        }
    }

    /**
     * Closes the load: stamps {@code last_seen} on every slug the load named, retires what
     * is older than {@link #RETENTION}, and replaces the companion once if the document
     * changed. A failed write is logged at ERROR
     * ({@code automation.identity_write_failed}) and never thrown.
     *
     * @throws IllegalStateException if no load is open
     */
    public void endLoad() {
        List<Retired> retired = new ArrayList<>();
        Map<String, Object> document = null;
        long snapshotRevision;
        int automations;
        int touchedCount;
        int minted;
        int triggersMinted;
        lock.lock();
        try {
            requireLoading("endLoad()");
            loading = false;
            Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
            for (String slug : touched) {
                Entry entry = entries.get(slug);
                if (entry != null && now.isAfter(entry.lastSeen)) {
                    entry.lastSeen = now;
                    revision++;
                }
            }
            Instant horizon = now.minus(RETENTION);
            for (Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator();
                    it.hasNext();) {
                Map.Entry<String, Entry> candidate = it.next();
                if (candidate.getValue().lastSeen.isBefore(horizon)) {
                    retired.add(new Retired(candidate.getKey(), candidate.getValue().id,
                            candidate.getValue().lastSeen));
                    it.remove();
                    revision++;
                }
            }
            if (revision != persistedRevision) {
                document = toDocument();
            }
            snapshotRevision = revision;
            automations = entries.size();
            touchedCount = touched.size();
            minted = mintedThisLoad;
            triggersMinted = triggersMintedThisLoad;
        } finally {
            lock.unlock();
        }

        for (Retired entry : retired) {
            LOG.info("automation.identity_retired: slug={} id={} last_seen={}",
                    entry.slug(), entry.id(), entry.lastSeen());
        }
        String written = "false";
        if (document != null) {
            try {
                companion.replace(document);
                markPersisted(snapshotRevision);
                written = "true";
            } catch (RuntimeException failure) {
                // R3: a write failure must not brick a boot. The identities stay valid in
                // memory, the store stays dirty, and the next endLoad() retries.
                LOG.error("automation.identity_write_failed: path={} cause={}", companion,
                        failure.getCause() != null ? failure.getCause() : failure.getMessage(),
                        failure);
                written = "failed";
            }
        }
        LOG.info("automation.identity_load_ended: path={} automations={} touched={} minted={} "
                        + "triggers_minted={} retired={} written={}",
                companion, automations, touchedCount, minted, triggersMinted, retired.size(),
                written);
    }

    @Override
    public AutomationId automationIdFor(String automationSlug) {
        Objects.requireNonNull(automationSlug, "automationSlug must not be null");
        lock.lock();
        try {
            requireLoading("automationIdFor(\"" + automationSlug + "\")");
            return entryFor(automationSlug).id;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public String generatedTriggerIdFor(String automationSlug, int triggerIndex) {
        Objects.requireNonNull(automationSlug, "automationSlug must not be null");
        if (triggerIndex < 0) {
            throw new IllegalArgumentException("triggerIndex must be >= 0: " + triggerIndex);
        }
        lock.lock();
        try {
            requireLoading("generatedTriggerIdFor(\"" + automationSlug + "\", "
                    + triggerIndex + ")");
            Entry entry = entryFor(automationSlug);
            String triggerId = entry.triggers.get(triggerIndex);
            if (triggerId == null) {
                triggerId = UlidFactory.generate(clock).toString();
                entry.triggers.put(triggerIndex, triggerId);
                triggersMintedThisLoad++;
                revision++;
            }
            return triggerId;
        } finally {
            lock.unlock();
        }
    }

    // ── guarded by lock ─────────────────────────────────────────────────────

    /** The slug's entry — minted on first encounter — marked as named by this load. */
    private Entry entryFor(String slug) {
        Entry entry = entries.get(slug);
        if (entry == null) {
            entry = new Entry(AutomationId.of(UlidFactory.generate(clock)),
                    clock.instant().truncatedTo(ChronoUnit.SECONDS));
            entries.put(slug, entry);
            mintedThisLoad++;
            revision++;
        }
        touched.add(slug);
        return entry;
    }

    private void requireLoading(String call) {
        if (!loading) {
            throw new IllegalStateException(call + " outside a load: beginLoad() must open "
                    + "every AutomationDefinitionLoader.load over this store and endLoad() "
                    + "must close it — identities minted outside the bracket are never "
                    + "persisted (path=" + companion + ")");
        }
    }

    /** The document, in its deterministic order: slugs sorted, trigger indices numeric. */
    private Map<String, Object> toDocument() {
        Map<String, Object> automations = new LinkedHashMap<>();
        for (Map.Entry<String, Entry> slugEntry : entries.entrySet()) {
            Entry entry = slugEntry.getValue();
            Map<String, Object> triggers = new LinkedHashMap<>();
            entry.triggers.forEach((index, triggerId) ->
                    triggers.put(Integer.toString(index), triggerId));
            Map<String, Object> rendered = new LinkedHashMap<>();
            rendered.put(KEY_ID, entry.id.toString());
            rendered.put(KEY_LAST_SEEN, entry.lastSeen.toString());
            rendered.put(KEY_TRIGGERS, triggers);
            automations.put(slugEntry.getKey(), rendered);
        }
        Map<String, Object> document = new LinkedHashMap<>();
        document.put(KEY_SCHEMA_VERSION, SCHEMA_VERSION);
        document.put(KEY_AUTOMATIONS, automations);
        return document;
    }

    private void markPersisted(long snapshotRevision) {
        lock.lock();
        try {
            persistedRevision = Math.max(persistedRevision, snapshotRevision);
        } finally {
            lock.unlock();
        }
    }

    // ── the fail-closed read (pure; no lock) ────────────────────────────────

    private TreeMap<String, Entry> parse(Map<String, Object> document) {
        requireKnownKeys(document, DOCUMENT_KEYS, "the document");
        Object version = document.get(KEY_SCHEMA_VERSION);
        boolean supported = (version instanceof Integer || version instanceof Long)
                && ((Number) version).longValue() == SCHEMA_VERSION;
        if (!supported) {
            throw malformed("schema_version is " + render(version) + ", not " + SCHEMA_VERSION);
        }

        TreeMap<String, Entry> parsed = new TreeMap<>();
        Map<AutomationId, String> slugById = new LinkedHashMap<>();
        Map<?, ?> automations = requireMap(document.get(KEY_AUTOMATIONS), KEY_AUTOMATIONS);
        for (Map.Entry<?, ?> slugEntry : automations.entrySet()) {
            String slug = requireStringKey(slugEntry.getKey(), KEY_AUTOMATIONS);
            String where = KEY_AUTOMATIONS + "." + slug;
            Map<?, ?> rendered = requireMap(slugEntry.getValue(), where);
            requireKnownKeys(rendered, ENTRY_KEYS, where);

            AutomationId id = AutomationId.of(requireUlid(rendered.get(KEY_ID), where + ".id"));
            String other = slugById.putIfAbsent(id, slug);
            if (other != null) {
                throw malformed("id " + id + " is under two slugs: '" + other + "' and '"
                        + slug + "'");
            }
            Entry entry = new Entry(id,
                    requireInstant(rendered.get(KEY_LAST_SEEN), where + ".last_seen"));
            Object triggers = rendered.get(KEY_TRIGGERS);
            if (triggers != null) {
                for (Map.Entry<?, ?> trigger
                        : requireMap(triggers, where + ".triggers").entrySet()) {
                    String key = requireStringKey(trigger.getKey(), where + ".triggers");
                    entry.triggers.put(requireIndex(key, where + ".triggers"),
                            requireUlid(trigger.getValue(), where + ".triggers." + key)
                                    .toString());
                }
            }
            parsed.put(slug, entry);
        }
        return parsed;
    }

    private void requireKnownKeys(Map<?, ?> map, Set<String> known, String where) {
        for (Object key : map.keySet()) {
            if (!(key instanceof String name) || !known.contains(name)) {
                throw malformed("unknown key " + render(key) + " in " + where);
            }
        }
    }

    private Map<?, ?> requireMap(Object value, String where) {
        if (value instanceof Map<?, ?> map) {
            return map;
        }
        throw malformed(where + " is " + render(value) + ", not a mapping");
    }

    /**
     * Any string is a key: the loader hands this store whatever slug a definition
     * carries, and a document this class wrote must never be one it refuses to read.
     */
    private String requireStringKey(Object key, String where) {
        if (key instanceof String name) {
            return name;
        }
        throw malformed("key " + render(key) + " in " + where + " is not a string");
    }

    private Ulid requireUlid(Object value, String where) {
        if (value instanceof String text && Ulid.isValid(text)) {
            return Ulid.parse(text);
        }
        throw malformed(where + " is " + render(value) + ", not a ULID");
    }

    private Instant requireInstant(Object value, String where) {
        if (value instanceof String text) {
            try {
                return Instant.parse(text);
            } catch (DateTimeException notAnInstant) {
                // falls through to the fail-closed throw
            }
        }
        throw malformed(where + " is " + render(value) + ", not an ISO-8601 instant");
    }

    /** A trigger index as this class writes it: the canonical decimal of an int {@code >= 0}. */
    private int requireIndex(String key, String where) {
        try {
            int index = Integer.parseInt(key);
            if (index >= 0 && Integer.toString(index).equals(key)) {
                return index;
            }
        } catch (NumberFormatException notAnIndex) {
            // falls through to the fail-closed throw
        }
        throw malformed("key '" + key + "' in " + where + " is not a trigger index");
    }

    private IllegalStateException malformed(String cause) {
        return new IllegalStateException("automation identity companion is malformed: path="
                + companion + " cause=" + cause + " — the file is engine-managed (Doc 07 §4.1)"
                + " and identities are never re-minted over it; restore it, or remove it to"
                + " mint new identities (the run history under the old ones is orphaned)");
    }

    private static String render(Object value) {
        return value == null ? "absent" : "'" + value + "'";
    }

    // ── values ──────────────────────────────────────────────────────────────

    /** One slug's identity; {@code lastSeen} and {@code triggers} mutate under the lock. */
    private static final class Entry {

        private final AutomationId id;
        private final TreeMap<Integer, String> triggers = new TreeMap<>();
        private Instant lastSeen;

        private Entry(AutomationId id, Instant lastSeen) {
            this.id = id;
            this.lastSeen = lastSeen;
        }
    }

    /** What {@link #endLoad()} retired, logged after the lock is released. */
    private record Retired(String slug, AutomationId id, Instant lastSeen) {
    }
}
