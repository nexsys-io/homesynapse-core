/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homesynapse.event.DomainEvent;
import com.homesynapse.event.EventDraft;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventOrigin;
import com.homesynapse.event.EventPage;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.PresenceSignalEvent;
import com.homesynapse.event.StateReportedEvent;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.HomeId;
import com.homesynapse.platform.identity.Ulid;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for the M6.3 at-rest payload-encryption write/read path
 * (Doc 15 §3.4, §13.2, §13.4) — the persistence-side half of OR-M6-NONCE.
 *
 * <p>The store is wired with a {@link CountingPayloadCipher} (a durable
 * counter-nonce test double; the production {@code StandardScopeKeyManager}
 * counter durability is proven in the config module's
 * {@code ScopeKeyManagerPayloadNonceTest}, which the persistence module cannot
 * import). Sensitive-PII scope events ({@code presence_signal} →
 * {@code presence_personal}) are encrypted-on-write; everything else stays
 * plaintext-at-rest.</p>
 *
 * <p><strong>The close gate</strong> is
 * {@link #killMidEncryptThenRestart_nonceNeverRepeats()} — the §13.4
 * OR-M6-NONCE condition.</p>
 *
 * <p><strong>Read discipline.</strong> Decrypted round-trips go through the
 * live store (its own read executor). Raw column assertions open a foreign
 * JDBC connection, which must happen only AFTER the {@link DatabaseExecutor}
 * is shut down — a live executor's connections block a foreign reader on
 * Windows (the {@code PersistenceFactoryAbandonTest} precedent).</p>
 *
 * <p>Clock is injected fixed (self-enforced {@code NO_DIRECT_TIME_ACCESS}
 * convention for this module's test sources).</p>
 */
@DisplayName("At-rest payload encryption write path (Doc 15 §3.4/§13.4, M6.3)")
final class AtRestEncryptionWritePathTest {

    private static final String EVENTS_MIGRATION_PATH = "db/migration/events";
    private static final List<String> EVENTS_MIGRATION_FILES = List.of(
            "V001__initial_event_store_schema.sql",
            "V002__subscriber_dead_letter_queue.sql",
            "V003__add_snapshots_and_drop_redundant_index.sql",
            "V004__dlq_operational_indices.sql",
            "V005__at_rest_payload_encryption_columns.sql");
    private static final DeploymentProfile PROFILE = DeploymentProfile.HOME;
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-06-13T00:00:00Z"), ZoneOffset.UTC);
    private static final HomeId TEST_HOME_ID =
            HomeId.of(Ulid.parse("01JAAAAAAAAAAAAAAAAAAAAAAA"));
    private static final EntityId ENTITY =
            new EntityId(Ulid.parse("01JBBBBBBBBBBBBBBBBBBBBBBB"));
    private static final Set<String> ENCRYPTED =
            Set.of("identity", "presence_personal");

    @TempDir
    Path tempDir;

    private DatabaseExecutor dbExecutor;

    /** Creates a new test instance. */
    AtRestEncryptionWritePathTest() {
        // Explicit constructor per -Xlint:all -Werror requirement.
    }

    @AfterEach
    void tearDown() {
        shutdownExecutor();
    }

    private void shutdownExecutor() {
        if (dbExecutor != null) {
            try {
                dbExecutor.shutdown();
            } catch (RuntimeException ignore) {
                // tear-down failure must not mask the primary assertion
            }
            dbExecutor = null;
        }
    }

    /** Builds a fresh store stack over {@code dbPath} with the given cipher + scopes. */
    private SqliteEventStore startStore(Path dbPath, PayloadCipher cipher, Set<String> scopes) {
        dbExecutor = new DatabaseExecutor(PROFILE, FIXED_CLOCK);
        dbExecutor.start(dbPath, EVENTS_MIGRATION_PATH, EVENTS_MIGRATION_FILES,
                MigrationConfig.freshInstall());
        List<Class<? extends DomainEvent>> classes =
                new ArrayList<>(AllEventClasses.ALL_EVENTS);
        EventTypeRegistry registry = new EventTypeRegistry(classes);
        ObjectMapper mapper = PersistenceObjectMapper.create();
        JacksonWarmup warmup = JacksonWarmup.warmup(mapper, registry);
        EventPayloadCodec codec = new EventPayloadCodec(registry, warmup);
        return new SqliteEventStore(
                dbExecutor, codec, registry, FIXED_CLOCK, TEST_HOME_ID, cipher, scopes);
    }

    private static EventDraft presenceDraft(String data) {
        return new EventDraft(EventTypes.PRESENCE_SIGNAL, 1, null,
                SubjectRef.entity(ENTITY), EventPriority.DIAGNOSTIC,
                EventOrigin.DEVICE_AUTONOMOUS,
                new PresenceSignalEvent("wifi_probe", "router", data), null, null);
    }

    private static EventDraft stateDraft(String value) {
        return new EventDraft(EventTypes.STATE_REPORTED, 1, null,
                SubjectRef.entity(ENTITY), EventPriority.DIAGNOSTIC,
                EventOrigin.DEVICE_AUTONOMOUS,
                new StateReportedEvent("power", value, null, null, null), null, null);
    }

    // ──────────────────────────────────────────────────────────────────
    // §13.2 — encrypt-on-write membership
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a sensitive-PII (presence) event persists encrypted with payload_iv + dek_ref"
            + " and reads back decrypted (INV-PD-03)")
    void sensitiveScope_persistsEncrypted() throws Exception {
        Path dbPath = tempDir.resolve("events.db");
        CountingPayloadCipher cipher = new CountingPayloadCipher(tempDir.resolve("nonce.json"));
        SqliteEventStore store = startStore(dbPath, cipher, ENCRYPTED);

        PresenceSignalEvent original = new PresenceSignalEvent("wifi_probe", "router", "probe-1");
        store.publishRoot(new EventDraft(EventTypes.PRESENCE_SIGNAL, 1, null,
                SubjectRef.entity(ENTITY), EventPriority.DIAGNOSTIC,
                EventOrigin.DEVICE_AUTONOMOUS, original, null, null));

        // Read-back through the live store (with the cipher) decrypts to the original.
        EventPage page = store.readFrom(0L, 10);
        assertThat(page.events()).hasSize(1);
        assertThat(page.events().get(0).payload()).isEqualTo(original);

        // Stored at rest: ciphertext payload + non-null counter nonce + dek_ref.
        shutdownExecutor();
        List<StoredRow> rows = rawRows(dbPath);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).dekRef()).isEqualTo("presence_personal:1");
        assertThat(rows.get(0).payloadIv()).isNotNull().hasSize(12);
    }

    @Test
    @DisplayName("a non-sensitive (device-state) event persists plaintext with NULL"
            + " payload_iv/dek_ref")
    void nonSensitiveScope_persistsPlaintext() throws Exception {
        Path dbPath = tempDir.resolve("events.db");
        CountingPayloadCipher cipher = new CountingPayloadCipher(tempDir.resolve("nonce.json"));
        SqliteEventStore store = startStore(dbPath, cipher, ENCRYPTED);

        store.publishRoot(stateDraft("on"));

        shutdownExecutor();
        List<StoredRow> rows = rawRows(dbPath);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).dekRef()).isNull();
        assertThat(rows.get(0).payloadIv()).isNull();
    }

    @Test
    @DisplayName("a mixed encrypted/plaintext corpus hydrates every event correctly")
    void mixedCorpus_readsBackCorrectly() throws Exception {
        Path dbPath = tempDir.resolve("events.db");
        CountingPayloadCipher cipher = new CountingPayloadCipher(tempDir.resolve("nonce.json"));
        SqliteEventStore store = startStore(dbPath, cipher, ENCRYPTED);

        store.publishRoot(presenceDraft("p-A"));
        store.publishRoot(stateDraft("on"));
        store.publishRoot(presenceDraft("p-B"));
        store.publishRoot(stateDraft("off"));

        List<EventEnvelope> events = store.readFrom(0L, 100).events();
        assertThat(events).hasSize(4);
        assertThat(events.get(0).payload())
                .isEqualTo(new PresenceSignalEvent("wifi_probe", "router", "p-A"));
        assertThat(events.get(1).payload())
                .isEqualTo(new StateReportedEvent("power", "on", null, null, null));
        assertThat(events.get(2).payload())
                .isEqualTo(new PresenceSignalEvent("wifi_probe", "router", "p-B"));
        assertThat(events.get(3).payload())
                .isEqualTo(new StateReportedEvent("power", "off", null, null, null));
    }

    // ──────────────────────────────────────────────────────────────────
    // §13.4 — OR-M6-NONCE close gate
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("kill mid-encrypt then restart: the per-scope counter never re-stores a"
            + " used nonce; the high-water survives the crash (OR-M6-NONCE)")
    void killMidEncryptThenRestart_nonceNeverRepeats() throws Exception {
        Path dbPath = tempDir.resolve("events.db");
        Path nonceFile = tempDir.resolve("nonce.json");

        // Pre-crash session: publish 5 presence events (counters 1..5 stored).
        CountingPayloadCipher cipher1 = new CountingPayloadCipher(nonceFile);
        SqliteEventStore store1 = startStore(dbPath, cipher1, ENCRYPTED);
        for (int i = 0; i < 5; i++) {
            store1.publishRoot(presenceDraft("pre-" + i));
        }
        // Kill MID-encrypt: burn one counter (advance + persist the high-water)
        // without ever inserting it — the catastrophic case the durability
        // discipline must tolerate as a harmless GAP, never a reuse.
        long burned = counterOf(cipher1.encrypt("presence_personal",
                "never-inserted".getBytes(StandardCharsets.UTF_8)).iv());
        shutdownExecutor(); // crash: no graceful checkpoint

        // Restart: a fresh cipher re-inits from the persisted high-water mark.
        CountingPayloadCipher cipher2 = new CountingPayloadCipher(nonceFile);
        SqliteEventStore store2 = startStore(dbPath, cipher2, ENCRYPTED);
        for (int i = 0; i < 3; i++) {
            store2.publishRoot(presenceDraft("post-" + i));
        }
        shutdownExecutor();

        // Every STORED nonce is distinct (no reuse under the one DEK); the
        // burned counter was never stored; and the post-restart writes resumed
        // strictly above the pre-crash maximum (a gap, not a reuse).
        List<StoredRow> rows = rawRows(dbPath);
        assertThat(rows).hasSize(8); // 5 pre + 3 post; the burned nonce was never inserted

        Set<String> nonces = new HashSet<>();
        long maxPre = 0;
        long minPost = Long.MAX_VALUE;
        for (int i = 0; i < rows.size(); i++) {
            byte[] iv = rows.get(i).payloadIv();
            assertThat(nonces.add(Arrays.toString(iv)))
                    .as("stored nonce %d must be unique under the DEK", i)
                    .isTrue();
            long c = counterOf(iv);
            assertThat(c).as("the burned counter %d was never stored", burned)
                    .isNotEqualTo(burned);
            if (i < 5) {
                maxPre = Math.max(maxPre, c);
            } else {
                minPost = Math.min(minPost, c);
            }
        }
        assertThat(minPost)
                .as("post-restart counters resume strictly above the pre-crash maximum")
                .isGreaterThan(maxPre);
    }

    @Test
    @DisplayName("a restore that rotates the DEK never reuses a (key, nonce) pair: the"
            + " write path records the new key_version (DP-D non-preclusion)")
    void restoreCannotResumeUsedCounter() throws Exception {
        Path dbPath = tempDir.resolve("events.db");
        CountingPayloadCipher cipher = new CountingPayloadCipher(tempDir.resolve("nonce.json"));
        SqliteEventStore store = startStore(dbPath, cipher, ENCRYPTED);

        store.publishRoot(presenceDraft("v1"));            // dek_ref presence_personal:1, nonce 1
        // Simulate restore-from-backup: rather than resume the version-1
        // counter (which could reuse a nonce), rotate the DEK. The version-2
        // counter restarts at 1, but under a DIFFERENT key — so the (key,
        // nonce) pair is fresh even though the nonce bytes repeat.
        cipher.rotate();
        store.publishRoot(presenceDraft("v2"));            // dek_ref presence_personal:2, nonce 1

        // Both round-trip through the live store.
        List<EventEnvelope> events = store.readFrom(0L, 10).events();
        assertThat(events.get(0).payload())
                .isEqualTo(new PresenceSignalEvent("wifi_probe", "router", "v1"));
        assertThat(events.get(1).payload())
                .isEqualTo(new PresenceSignalEvent("wifi_probe", "router", "v2"));

        shutdownExecutor();
        List<StoredRow> rows = rawRows(dbPath);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).dekRef()).isEqualTo("presence_personal:1");
        assertThat(rows.get(1).dekRef()).isEqualTo("presence_personal:2");
        // The two nonces are byte-equal (both counter 1) — proof that safety
        // comes from the rotated KEY, not a distinct nonce. The write path
        // recorded distinct key versions, so a restore CAN rotate rather than
        // reuse (the property M6.3 must not preclude).
        assertThat(rows.get(0).payloadIv()).isEqualTo(rows.get(1).payloadIv());
    }

    // ──────────────────────────────────────────────────────────────────
    // Fail-closed
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a sensitive-scope event with encryption enabled but no cipher fails closed"
            + " — no plaintext sensitive-PII write (Doc 15 §6)")
    void encryptionEnabled_nullCipher_failsClosed() {
        Path dbPath = tempDir.resolve("events.db");
        // Enabled scopes but a null cipher — the misconfiguration the gate must
        // refuse rather than silently write plaintext.
        SqliteEventStore store = startStore(dbPath, null, Set.of("presence_personal"));

        assertThatThrownBy(() -> store.publishRoot(presenceDraft("must-not-write")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no PayloadCipher is wired");
    }

    @Test
    @DisplayName("a null cipher still writes non-sensitive events plaintext (no false fail-closed)")
    void nullCipher_nonSensitiveStillPlaintext() throws Exception {
        Path dbPath = tempDir.resolve("events.db");
        SqliteEventStore store = startStore(dbPath, null, Set.of("presence_personal"));

        store.publishRoot(stateDraft("on"));

        shutdownExecutor();
        List<StoredRow> rows = rawRows(dbPath);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).dekRef()).isNull();
        assertThat(rows.get(0).payloadIv()).isNull();
    }

    // ──────────────────────────────────────────────────────────────────
    // Raw-JDBC row inspection (asserts the at-rest stored bytes/columns).
    // Opened only after the executor is shut down (Windows file-lock safety).
    // ──────────────────────────────────────────────────────────────────

    private record StoredRow(String eventType, byte[] payloadIv, String dekRef) {
    }

    private static List<StoredRow> rawRows(Path dbPath) throws SQLException {
        List<StoredRow> rows = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            try (Statement pragma = conn.createStatement()) {
                pragma.execute("PRAGMA busy_timeout = 5000");
            }
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT event_type, payload_iv, dek_ref FROM events "
                                 + "ORDER BY global_position ASC")) {
                while (rs.next()) {
                    rows.add(new StoredRow(
                            rs.getString("event_type"),
                            rs.getBytes("payload_iv"),
                            rs.getString("dek_ref")));
                }
            }
        }
        return rows;
    }

    /** Decodes the counter from a 96-bit nonce (big-endian trailing 8 bytes). */
    private static long counterOf(byte[] nonce) {
        return ByteBuffer.wrap(nonce).getLong(nonce.length - Long.BYTES);
    }
}
