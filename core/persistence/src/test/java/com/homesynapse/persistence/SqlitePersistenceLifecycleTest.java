/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import com.homesynapse.event.DomainEvent;
import com.homesynapse.event.EventDraft;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventOrigin;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.PresenceSignalEvent;
import com.homesynapse.event.SequenceConflictException;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.event.test.EventStoreContractTest;
import com.homesynapse.event.test.TestEventTypes;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.HomeId;
import com.homesynapse.platform.identity.Ulid;
import com.homesynapse.platform.identity.UlidFactory;
import com.homesynapse.state.CheckpointRecord;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Lifecycle integration tests for {@link SqlitePersistenceLifecycle} —
 * verifies the full initialization, shutdown, restart, and store wiring
 * that forms the capstone of the M2 persistence milestone.
 *
 * <p>Each test method gets a fresh {@link TempDir} and constructs a new
 * lifecycle instance. The {@link #tearDown()} method calls
 * {@link SqlitePersistenceLifecycle#stop()} to release all database
 * connections and prevent file-lock leaks.</p>
 *
 * <p>The event type registry is seeded with the full production event set
 * ({@link AllEventClasses#ALL_EVENTS}) plus the contract test's fixture
 * payload ({@link EventStoreContractTest.TestPayload}) — this mirrors the
 * pattern established by {@link SqliteEventStoreTest} and ensures the
 * Jackson warmup produces a reader/writer for the test payload type.</p>
 *
 * @see SqlitePersistenceLifecycle
 */
@DisplayName("SqlitePersistenceLifecycle — persistence capstone lifecycle tests")
final class SqlitePersistenceLifecycleTest {

    /** Persistence configuration — HOME profile (2 read threads, AMD-27). */
    private static final PersistenceConfig CONFIG = PersistenceConfig.HOME_DEFAULT;

    /**
     * Fixed clock — NO_DIRECT_TIME_ACCESS ArchUnit rule forbids
     * {@code Clock.systemUTC()} in {@code com.homesynapse.persistence}.
     */
    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-01-15T12:00:00Z"), ZoneOffset.UTC);

    /** Test home identity (AMD-34). */
    private static final HomeId TEST_HOME_ID =
            HomeId.of(Ulid.parse("01JAAAAAAAAAAAAAAAAAAAAAAA"));

    /**
     * All event classes for the registry: production events + the contract
     * test fixture payload.
     */
    private static final List<Class<? extends DomainEvent>> ALL_TEST_CLASSES;

    static {
        var combined = new ArrayList<>(AllEventClasses.ALL_EVENTS);
        combined.add(EventStoreContractTest.TestPayload.class);
        ALL_TEST_CLASSES = List.copyOf(combined);
    }

    @TempDir
    Path tempDir;

    private SqlitePersistenceLifecycle lifecycle;

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    SqlitePersistenceLifecycleTest() {
    }

    @BeforeEach
    void setUp() {
        lifecycle = createLifecycle(tempDir);
    }

    @AfterEach
    void tearDown() {
        if (lifecycle != null) {
            lifecycle.stop();
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Test 1: Fresh database — creates and initializes
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("start on fresh database creates file and initializes stores")
    void start_freshDatabase_createsAndInitializes() throws SequenceConflictException {
        Path dbPath = tempDir.resolve("events.db");
        assertThat(dbPath).doesNotExist();

        lifecycle.start().join();

        // Database file exists
        assertThat(dbPath).exists();

        // All store accessors return non-null objects
        assertThat(lifecycle.eventStore()).isNotNull();
        assertThat(lifecycle.checkpointStore()).isNotNull();
        assertThat(lifecycle.viewCheckpointStore()).isNotNull();
        assertThat(lifecycle.atomicCheckpointWriter()).isNotNull();

        // EventStore can publish and read an event (end-to-end)
        EventEnvelope published = publishTestEvent(lifecycle);
        assertThat(published).isNotNull();
        assertThat(published.globalPosition()).isGreaterThan(0);

        List<EventEnvelope> readBack = lifecycle.eventStore()
                .readFrom(0, 10).events();
        assertThat(readBack).hasSize(1);
        assertThat(readBack.getFirst().eventId())
                .isEqualTo(published.eventId());

        // CheckpointStore can write and read a checkpoint
        lifecycle.checkpointStore().writeCheckpoint("test-sub", 42L);
        long position = lifecycle.checkpointStore()
                .readCheckpoint("test-sub");
        assertThat(position).isEqualTo(42L);

        // ViewCheckpointStore can write and read a view checkpoint
        byte[] viewData = "snapshot-data".getBytes();
        lifecycle.viewCheckpointStore()
                .writeCheckpoint("test-view", 42L, viewData);
        Optional<CheckpointRecord> viewRecord = lifecycle
                .viewCheckpointStore().readLatestCheckpoint("test-view");
        assertThat(viewRecord).isPresent();
        assertThat(viewRecord.get().position()).isEqualTo(42L);
    }

    // ──────────────────────────────────────────────────────────────────
    // Test 2: Existing database — resumes correctly
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("start on existing database resumes with prior data intact")
    void start_existingDatabase_resumesCorrectly() throws SequenceConflictException {
        // First lifecycle: publish events, write checkpoints
        lifecycle.start().join();

        EventEnvelope published = publishTestEvent(lifecycle);
        lifecycle.checkpointStore().writeCheckpoint("sub-1", 100L);
        byte[] viewData = "view-snapshot".getBytes();
        lifecycle.viewCheckpointStore()
                .writeCheckpoint("view-1", 100L, viewData);

        lifecycle.stop();
        lifecycle = null;

        // Second lifecycle: same database path
        SqlitePersistenceLifecycle lifecycle2 = createLifecycle(tempDir);
        try {
            lifecycle2.start().join();

            // Published events are still readable
            List<EventEnvelope> events = lifecycle2.eventStore()
                    .readFrom(0, 10).events();
            assertThat(events).hasSize(1);
            assertThat(events.getFirst().eventId())
                    .isEqualTo(published.eventId());

            // Checkpoints are still readable
            long pos = lifecycle2.checkpointStore()
                    .readCheckpoint("sub-1");
            assertThat(pos).isEqualTo(100L);

            Optional<CheckpointRecord> view = lifecycle2
                    .viewCheckpointStore()
                    .readLatestCheckpoint("view-1");
            assertThat(view).isPresent();
            assertThat(view.get().position()).isEqualTo(100L);

            // New events can be published
            EventEnvelope newEvent = publishTestEvent(lifecycle2);
            assertThat(newEvent.globalPosition())
                    .isGreaterThan(published.globalPosition());
        } finally {
            lifecycle2.stop();
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Test 3: Stop flushes WAL and closes cleanly
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("stop flushes WAL and closes database cleanly")
    void stop_flushesWalAndClosesCleanly() throws SequenceConflictException {
        Path dbPath = tempDir.resolve("events.db");
        lifecycle.start().join();

        // Publish events to generate WAL content
        publishTestEvent(lifecycle);

        // Stop should flush the WAL
        lifecycle.stop();

        // WAL file is either absent or zero bytes (TRUNCATE checkpoint)
        Path walFile = tempDir.resolve("events.db-wal");
        if (Files.exists(walFile)) {
            try {
                assertThat(Files.size(walFile)).isZero();
            } catch (Exception e) {
                // File may have been deleted between exists check and
                // size check — race is acceptable; absence means success.
            }
        }

        // Database file exists and is valid
        assertThat(dbPath).exists();

        // Calling stop() again is safe (idempotent)
        lifecycle.stop();
        lifecycle = null;
    }

    // ──────────────────────────────────────────────────────────────────
    // Test 4: Full lifecycle cycle — start/stop/restart
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("full lifecycle: start → publish → stop → restart → verify")
    void fullLifecycleCycle_startStopRestart() throws SequenceConflictException {
        // Start and publish
        lifecycle.start().join();
        EventEnvelope event1 = publishTestEvent(lifecycle);
        lifecycle.checkpointStore().writeCheckpoint("sub-cycle", 50L);

        // Stop
        lifecycle.stop();
        lifecycle = null;

        // Restart with a new lifecycle instance
        SqlitePersistenceLifecycle lifecycle2 = createLifecycle(tempDir);
        try {
            lifecycle2.start().join();

            // Verify all data survives
            List<EventEnvelope> events = lifecycle2.eventStore()
                    .readFrom(0, 10).events();
            assertThat(events).isNotEmpty();
            assertThat(events.getFirst().eventId())
                    .isEqualTo(event1.eventId());

            long pos = lifecycle2.checkpointStore()
                    .readCheckpoint("sub-cycle");
            assertThat(pos).isEqualTo(50L);

            // Publish more events
            EventEnvelope event2 = publishTestEvent(lifecycle2);
            assertThat(event2.globalPosition())
                    .isGreaterThan(event1.globalPosition());
        } finally {
            lifecycle2.stop();
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Test 5: Store accessors before start throw ISE
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("store accessors before start throw IllegalStateException")
    void storeAccessors_beforeStart_throwsISE() {
        assertThatThrownBy(() -> lifecycle.eventStore())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not started");

        assertThatThrownBy(() -> lifecycle.checkpointStore())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not started");

        assertThatThrownBy(() -> lifecycle.viewCheckpointStore())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not started");

        assertThatThrownBy(() -> lifecycle.atomicCheckpointWriter())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not started");
    }

    // ──────────────────────────────────────────────────────────────────
    // Test 6: Start is idempotent
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("start is idempotent — second call returns completed future")
    void start_idempotent() {
        lifecycle.start().join();

        // Second start returns immediately with no errors
        CompletableFuture<Void> second = lifecycle.start();
        assertThat(second).isCompleted();
        assertThat(second.isCompletedExceptionally()).isFalse();

        // Stores still work
        assertThat(lifecycle.eventStore()).isNotNull();
    }

    // ──────────────────────────────────────────────────────────────────
    // Test 7: Stop before start is a no-op
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("stop before start is a safe no-op")
    void stop_beforeStart_noOp() {
        // No exceptions should be thrown
        lifecycle.stop();

        // Can still start afterwards
        lifecycle.start().join();
        assertThat(lifecycle.eventStore()).isNotNull();
    }

    // ──────────────────────────────────────────────────────────────────
    // Test 8: createBackup throws UnsupportedOperationException
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("createBackup throws UnsupportedOperationException")
    void createBackup_throwsUOE() {
        assertThatThrownBy(
                () -> lifecycle.createBackup(new BackupOptions(false, false)))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("Backup not yet implemented");
    }

    // ──────────────────────────────────────────────────────────────────
    // Test 9: restoreFromBackup throws UnsupportedOperationException
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("restoreFromBackup throws UnsupportedOperationException")
    void restoreFromBackup_throwsUOE() {
        assertThatThrownBy(
                () -> lifecycle.restoreFromBackup(tempDir))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("Restore not yet implemented");
    }

    // ──────────────────────────────────────────────────────────────────
    // Test 10: AtomicCheckpointWriter functional through lifecycle
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("atomicCheckpointWriter writes both tables atomically")
    void atomicCheckpointWriter_functionalThroughLifecycle() throws SequenceConflictException {
        lifecycle.start().join();

        byte[] viewData = "atomic-snapshot".getBytes();
        lifecycle.atomicCheckpointWriter()
                .writeAtomicCheckpoint("atomic-sub", 77L,
                        "atomic-view", viewData);

        // Verify subscriber checkpoint via CheckpointStore
        long subscriberPos = lifecycle.checkpointStore()
                .readCheckpoint("atomic-sub");
        assertThat(subscriberPos).isEqualTo(77L);

        // Verify view checkpoint via ViewCheckpointStore
        Optional<CheckpointRecord> viewRecord = lifecycle
                .viewCheckpointStore()
                .readLatestCheckpoint("atomic-view");
        assertThat(viewRecord).isPresent();
        assertThat(viewRecord.get().position()).isEqualTo(77L);
        assertThat(viewRecord.get().data()).isEqualTo(viewData);
    }

    // ──────────────────────────────────────────────────────────────────
    // AB-4 activation gate: cipher-presence flips the encrypted-scope set
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AB-4: a non-null cipher activates encrypt-on-write for the sensitive scopes;"
            + " a null cipher leaves every scope plaintext (the cipher-presence gate)")
    void cipherPresence_gatesEncryptedScopes() throws Exception {
        // The @BeforeEach lifecycle is unused here (constructed, never started —
        // a no-op stop()); these two lifecycles own their own database files.

        // WITH a cipher: the gate wires DEFAULT_ENCRYPTED_SCOPES, so a presence
        // event (→ presence_personal) is encrypted at rest with the F1 v1 byte.
        Path encDb = tempDir.resolve("enc-events.db");
        SqlitePersistenceLifecycle withCipher = new SqlitePersistenceLifecycle(
                encDb, CONFIG, FIXED_CLOCK, TEST_HOME_ID, ALL_TEST_CLASSES,
                new CountingPayloadCipher(tempDir.resolve("enc-nonce.json")));
        withCipher.start().join();
        withCipher.eventStore().publishRoot(presenceDraft("activated"));
        withCipher.stop();

        StoredRow encrypted = firstRow(encDb);
        assertThat(encrypted.dekRef()).isEqualTo("presence_personal:1");
        assertThat(encrypted.payload()).isNotNull();
        assertThat(encrypted.payload()[0]).isEqualTo((byte) 0x01); // F1 v1 envelope byte

        // WITHOUT a cipher: the gate leaves the enabled scope-set empty → the
        // same presence event stays plaintext-at-rest (NULL dek_ref).
        Path plainDb = tempDir.resolve("plain-events.db");
        SqlitePersistenceLifecycle noCipher = new SqlitePersistenceLifecycle(
                plainDb, CONFIG, FIXED_CLOCK, TEST_HOME_ID, ALL_TEST_CLASSES);
        noCipher.start().join();
        noCipher.eventStore().publishRoot(presenceDraft("plaintext"));
        noCipher.stop();

        assertThat(firstRow(plainDb).dekRef()).isNull();
    }

    private static EventDraft presenceDraft(String data) {
        EntityId entityId = new EntityId(UlidFactory.generate(FIXED_CLOCK));
        return new EventDraft(EventTypes.PRESENCE_SIGNAL, 1, null,
                SubjectRef.entity(entityId), EventPriority.DIAGNOSTIC,
                EventOrigin.DEVICE_AUTONOMOUS,
                new PresenceSignalEvent("wifi_probe", "router", data), null, null);
    }

    private record StoredRow(String dekRef, byte[] payload) {
    }

    /** Raw-reads the first row's {@code dek_ref}/{@code payload} after shutdown. */
    private static StoredRow firstRow(Path dbPath) throws SQLException {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            try (Statement pragma = conn.createStatement()) {
                pragma.execute("PRAGMA busy_timeout = 5000");
            }
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT dek_ref, payload FROM events "
                                 + "ORDER BY global_position ASC LIMIT 1")) {
                if (!rs.next()) {
                    throw new AssertionError("no row in events table");
                }
                return new StoredRow(rs.getString("dek_ref"), rs.getBytes("payload"));
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────

    /**
     * Creates a lifecycle instance pointing at the given temp directory.
     */
    private static SqlitePersistenceLifecycle createLifecycle(Path dir) {
        return new SqlitePersistenceLifecycle(
                dir.resolve("events.db"),
                CONFIG,
                FIXED_CLOCK,
                TEST_HOME_ID,
                ALL_TEST_CLASSES);
    }

    /**
     * Publishes a test event through the lifecycle's event store and
     * returns the persisted envelope.
     */
    private static EventEnvelope publishTestEvent(
            SqlitePersistenceLifecycle lc) throws SequenceConflictException {
        EntityId entityId = new EntityId(UlidFactory.generate(FIXED_CLOCK));
        SubjectRef subject = SubjectRef.entity(entityId);
        EventDraft draft = new EventDraft(
                TestEventTypes.TEST_EVENT,
                1,
                null,
                subject,
                EventPriority.NORMAL,
                EventOrigin.SYSTEM,
                new EventStoreContractTest.TestPayload("lifecycle-test"),
                null,
                null);
        return lc.eventStore().publishRoot(draft);
    }
}
