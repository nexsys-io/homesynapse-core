/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homesynapse.event.DomainEvent;
import com.homesynapse.event.EventDraft;
import com.homesynapse.event.EventOrigin;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.PresenceSignalEvent;
import com.homesynapse.event.SequenceConflictException;
import com.homesynapse.event.StateReportedEvent;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.HomeId;
import com.homesynapse.platform.identity.Ulid;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AB-2 — fail-closed read contract for the at-rest payload-encryption read path
 * (Doc 15 §6, OR-RF-DECRYPT). The companion to {@link AtRestEncryptionWritePathTest}'s
 * write-half: it asserts the READ path raises a typed, loud
 * {@link PayloadDecryptionException} on every decrypt failure and aborts the
 * <em>whole read batch</em> rather than skipping a row, degrading, or feeding
 * ciphertext to the codec.
 *
 * <p><strong>Failure domain pin (A2.2 / SD-9).</strong> The scope is
 * per-read-batch: a single undecryptable row fails the entire {@code readFrom}
 * batch — proven by {@link #mixedBatch_oneUndecryptableRow_abortsWholeBatch()},
 * where a readable plaintext row in the same page is NOT returned because the
 * batch aborts on the encrypted row. This is the intended MVP posture (A3): a
 * lost/corrupt root key makes the store loudly unreadable, never silently
 * partial.</p>
 *
 * <p>Construction mirrors {@code AtRestEncryptionWritePathTest.startStore}:
 * encrypted rows are written in a first session with a faithful
 * {@link CountingPayloadCipher}, the executor is shut down, then a second session
 * re-opens the same database with the failing read-side condition. Clock is
 * injected fixed (self-enforced {@code NO_DIRECT_TIME_ACCESS} for this module's
 * test sources).</p>
 */
@DisplayName("SqliteEventStore fail-closed decrypt read path (AB-2, Doc 15 §6)")
final class SqliteEventStoreDecryptFailClosedTest {

    private static final String EVENTS_MIGRATION_PATH = "db/migration/events";
    private static final List<String> EVENTS_MIGRATION_FILES = List.of(
            "V001__initial_event_store_schema.sql",
            "V002__subscriber_dead_letter_queue.sql",
            "V003__add_snapshots_and_drop_redundant_index.sql",
            "V004__dlq_operational_indices.sql",
            "V005__at_rest_payload_encryption_columns.sql");
    private static final DeploymentProfile PROFILE = DeploymentProfile.HOME;
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-06-19T00:00:00Z"), ZoneOffset.UTC);
    private static final HomeId TEST_HOME_ID =
            HomeId.of(Ulid.parse("01JAAAAAAAAAAAAAAAAAAAAAAA"));
    private static final EntityId ENTITY =
            new EntityId(Ulid.parse("01JBBBBBBBBBBBBBBBBBBBBBBB"));
    private static final Set<String> ENCRYPTED =
            Set.of("identity", "presence_personal");

    @TempDir
    Path tempDir;

    private DatabaseExecutor dbExecutor;

    /** Explicit constructor per {@code -Xlint:all -Werror}. */
    SqliteEventStoreDecryptFailClosedTest() {
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

    /** Writes one encrypted presence row at global_position 1, then shuts the executor down. */
    private void writeOneEncryptedRow(Path dbPath) throws SequenceConflictException {
        SqliteEventStore writer =
                startStore(dbPath, new CountingPayloadCipher(tempDir.resolve("nonce.json")), ENCRYPTED);
        writer.publishRoot(presenceDraft("sensitive-1"));
        shutdownExecutor();
    }

    /** A read-only cipher double whose {@code decrypt} always throws the supplied exception. */
    private static PayloadCipher decryptThrows(Supplier<RuntimeException> failure) {
        return new PayloadCipher() {
            @Override
            public EncryptedPayload encrypt(String scopeId, byte[] plaintext, byte[] aad) {
                throw new UnsupportedOperationException("read-only decrypt-failure stub");
            }

            @Override
            public byte[] decrypt(String scopeId, int keyVersion, byte[] ciphertext, byte[] iv,
                                  byte[] aad) {
                throw failure.get();
            }
        };
    }

    // ──────────────────────────────────────────────────────────────────
    // A2.4 — both decrypt-failure paths
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an encrypted row read with no cipher wired fails the batch closed"
            + " (NO_CIPHER_WIRED, carries global_position) — no plaintext, no silent fallback")
    void missingCipherOnRead_failsBatchClosed() throws SequenceConflictException {
        Path dbPath = tempDir.resolve("events.db");
        writeOneEncryptedRow(dbPath);

        // Re-open with NO cipher: the encrypted row must fail closed, never feed
        // ciphertext to the codec, never fall back to plaintext.
        SqliteEventStore reader = startStore(dbPath, null, ENCRYPTED);

        assertThatThrownBy(() -> reader.readFrom(0L, 10))
                .isInstanceOfSatisfying(PayloadDecryptionException.class, ex -> {
                    org.assertj.core.api.Assertions.assertThat(ex.failureKind())
                            .isEqualTo(PayloadDecryptionException.FailureKind.NO_CIPHER_WIRED);
                    org.assertj.core.api.Assertions.assertThat(ex.globalPosition()).isEqualTo(1L);
                });
    }

    @Test
    @DisplayName("an encrypted row whose cipher cannot decrypt fails the batch closed:"
            + " GCM auth failure and destroyed key are classified distinctly with the cause")
    void undecryptableKeyOnRead_failsBatchClosed() throws SequenceConflictException {
        Path dbPath = tempDir.resolve("events.db");
        writeOneEncryptedRow(dbPath);

        // CASE-b — GCM authentication failure (IllegalStateException from the cipher).
        SqliteEventStore gcmReader = startStore(dbPath,
                decryptThrows(() -> new IllegalStateException("GCM tag mismatch")), ENCRYPTED);
        assertThatThrownBy(() -> gcmReader.readFrom(0L, 10))
                .isInstanceOfSatisfying(PayloadDecryptionException.class, ex -> {
                    org.assertj.core.api.Assertions.assertThat(ex.failureKind())
                            .isEqualTo(PayloadDecryptionException.FailureKind.GCM_AUTH_FAILED);
                    org.assertj.core.api.Assertions.assertThat(ex.globalPosition()).isEqualTo(1L);
                    org.assertj.core.api.Assertions.assertThat(ex.scopeId())
                            .isEqualTo("presence_personal");
                    org.assertj.core.api.Assertions.assertThat(ex.keyVersion()).isEqualTo(1);
                });
        shutdownExecutor();

        // CASE-a — key absent or destroyed / crypto-shred (IllegalArgumentException).
        SqliteEventStore shreddedReader = startStore(dbPath,
                decryptThrows(() -> new IllegalArgumentException("key destroyed")), ENCRYPTED);
        assertThatThrownBy(() -> shreddedReader.readFrom(0L, 10))
                .isInstanceOfSatisfying(PayloadDecryptionException.class, ex -> {
                    org.assertj.core.api.Assertions.assertThat(ex.failureKind())
                            .isEqualTo(PayloadDecryptionException.FailureKind.KEY_ABSENT_OR_DESTROYED);
                    org.assertj.core.api.Assertions.assertThat(ex.globalPosition()).isEqualTo(1L);
                    org.assertj.core.api.Assertions.assertThat(ex.scopeId())
                            .isEqualTo("presence_personal");
                    org.assertj.core.api.Assertions.assertThat(ex.keyVersion()).isEqualTo(1);
                });
    }

    // ──────────────────────────────────────────────────────────────────
    // AB-4 F1 — the envelope version byte fails closed on an unknown value
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an encrypted row whose stored envelope leads with an unknown version byte"
            + " fails the batch closed (UNKNOWN_ENVELOPE_VERSION) — never implicit-v1, never"
            + " fed to the cipher")
    void unknownEnvelopeVersion_failsBatchClosed() throws SequenceConflictException, SQLException {
        Path dbPath = tempDir.resolve("events.db");
        writeOneEncryptedRow(dbPath);

        // Corrupt the stored at-rest envelope's leading version byte (v1 = 0x01)
        // to an unknown value. The DatabaseExecutor is already shut down inside
        // writeOneEncryptedRow, so a foreign JDBC writer is safe (Windows
        // file-lock discipline).
        overwriteLeadingEnvelopeByte(dbPath, (byte) 0x7F);

        // Re-open with a faithful cipher: the strict version parse rejects the
        // row BEFORE the cipher is consulted, so even a working cipher fails closed.
        SqliteEventStore reader = startStore(dbPath,
                new CountingPayloadCipher(tempDir.resolve("reader-nonce.json")), ENCRYPTED);
        assertThatThrownBy(() -> reader.readFrom(0L, 10))
                .isInstanceOfSatisfying(PayloadDecryptionException.class, ex -> {
                    assertThat(ex.failureKind())
                            .isEqualTo(PayloadDecryptionException.FailureKind.UNKNOWN_ENVELOPE_VERSION);
                    assertThat(ex.globalPosition()).isEqualTo(1L);
                    assertThat(ex.scopeId()).isEqualTo("presence_personal");
                    assertThat(ex.keyVersion()).isEqualTo(1);
                });
    }

    /**
     * Overwrites the first byte of the encrypted row's stored {@code payload}
     * BLOB (the F1 envelope version discriminator) with {@code newLeadingByte}.
     * Opened only after the executor is shut down (Windows file-lock safety).
     */
    private static void overwriteLeadingEnvelopeByte(Path dbPath, byte newLeadingByte)
            throws SQLException {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            try (Statement pragma = conn.createStatement()) {
                pragma.execute("PRAGMA busy_timeout = 5000");
            }
            long position;
            byte[] payload;
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT global_position, payload FROM events "
                                 + "WHERE dek_ref IS NOT NULL ORDER BY global_position ASC LIMIT 1")) {
                if (!rs.next()) {
                    throw new AssertionError("no encrypted row to corrupt");
                }
                position = rs.getLong("global_position");
                payload = rs.getBytes("payload");
            }
            payload[0] = newLeadingByte;
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE events SET payload = ? WHERE global_position = ?")) {
                ps.setBytes(1, payload);
                ps.setLong(2, position);
                ps.executeUpdate();
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // A2.2 — the failure domain is the whole read-batch (not per-row degrade)
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a single undecryptable row aborts the WHOLE read batch — a readable"
            + " plaintext row in the same page is not returned (per-batch scope, not per-row)")
    void mixedBatch_oneUndecryptableRow_abortsWholeBatch() throws SequenceConflictException {
        Path dbPath = tempDir.resolve("events.db");

        // global_position 1 = plaintext (state); 2 = encrypted (presence).
        SqliteEventStore writer =
                startStore(dbPath, new CountingPayloadCipher(tempDir.resolve("nonce.json")), ENCRYPTED);
        writer.publishRoot(stateDraft("on"));        // plaintext, position 1
        writer.publishRoot(presenceDraft("pii-1"));  // encrypted, position 2
        shutdownExecutor();

        // Re-open with no cipher: reading the whole page must abort on the
        // encrypted row — the readable plaintext row at position 1 is NOT
        // returned, proving the batch (not the row) is the failure domain.
        SqliteEventStore reader = startStore(dbPath, null, ENCRYPTED);
        assertThatThrownBy(() -> reader.readFrom(0L, 10))
                .isInstanceOf(PayloadDecryptionException.class);
    }
}
