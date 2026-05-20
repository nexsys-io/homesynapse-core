/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import com.homesynapse.state.CheckpointRecord;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link AtomicCheckpointWriter} — exercises atomicity, rollback,
 * position consistency, and concurrent read safety across the
 * {@code subscriber_checkpoints} and {@code view_checkpoints} tables.
 *
 * <p>This test class uses three persistence objects sharing the same
 * {@link DatabaseExecutor}: the {@link AtomicCheckpointWriter} under test,
 * a {@link SqliteCheckpointStore} for verifying subscriber checkpoint state
 * via {@code readCheckpoint()}, and a {@link SqliteViewCheckpointStore} for
 * verifying view checkpoint state via {@code readLatestCheckpoint()}.</p>
 *
 * @see AtomicCheckpointWriter
 */
@DisplayName("AtomicCheckpointWriter — same-transaction checkpoint composition")
final class AtomicCheckpointWriterTest {

    /** Classpath directory holding the events-database migration scripts. */
    private static final String EVENTS_MIGRATION_PATH = "db/migration/events";

    /** The ordered list of migration files the executor should apply. */
    private static final List<String> EVENTS_MIGRATION_FILES = List.of(
            "V001__initial_event_store_schema.sql");

    /** Deployment profile (HOME — 2 read threads, enough to exercise round-robin). */
    private static final DeploymentProfile PROFILE = DeploymentProfile.HOME;

    /**
     * Fixed clock for deterministic timestamps. Matches the convention used by
     * all persistence-module test classes.
     */
    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-01-15T12:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path tempDir;

    private DatabaseExecutor databaseExecutor;
    private AtomicCheckpointWriter writer;
    private SqliteCheckpointStore checkpointStore;
    private SqliteViewCheckpointStore viewCheckpointStore;

    /** Creates a new test instance. */
    AtomicCheckpointWriterTest() {
        // Explicit no-arg constructor for -Xlint:all -Werror builds.
    }

    @BeforeEach
    void setUp() {
        Path dbPath = tempDir.resolve("events.db");

        databaseExecutor = new DatabaseExecutor(PROFILE, FIXED_CLOCK);
        databaseExecutor.start(
                dbPath,
                EVENTS_MIGRATION_PATH,
                EVENTS_MIGRATION_FILES,
                MigrationConfig.freshInstall());

        writer = new AtomicCheckpointWriter(databaseExecutor, FIXED_CLOCK);
        checkpointStore = new SqliteCheckpointStore(databaseExecutor, FIXED_CLOCK);
        viewCheckpointStore = new SqliteViewCheckpointStore(databaseExecutor, FIXED_CLOCK);
    }

    @AfterEach
    void tearDown() {
        if (checkpointStore != null) {
            checkpointStore.clearThreadLocalForTesting();
        }
        if (viewCheckpointStore != null) {
            viewCheckpointStore.clearThreadLocalForTesting();
        }
        if (databaseExecutor != null) {
            databaseExecutor.shutdown();
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Test 1: Both tables updated
    // ──────────────────────────────────────────────────────────────────

    /**
     * Verifies that an atomic checkpoint write updates both the subscriber
     * checkpoint table and the view checkpoint table. This is the core
     * happy-path test — both stores must reflect the written values.
     */
    @Test
    @DisplayName("atomic write updates both subscriber and view checkpoint tables")
    void writeAtomicCheckpoint_bothTablesUpdated() {
        byte[] viewData = {1, 2, 3, 4, 5};

        writer.writeAtomicCheckpoint("state-projection", 50L,
                "entity_state", viewData);

        // Verify subscriber checkpoint
        long subscriberPosition = checkpointStore.readCheckpoint("state-projection");
        assertThat(subscriberPosition).isEqualTo(50L);

        // Verify view checkpoint
        Optional<CheckpointRecord> viewRecord =
                viewCheckpointStore.readLatestCheckpoint("entity_state");
        assertThat(viewRecord).isPresent();
        assertThat(viewRecord.get().position()).isEqualTo(50L);
        assertThat(viewRecord.get().data()).isEqualTo(viewData);
    }

    // ──────────────────────────────────────────────────────────────────
    // Test 2: Position consistency
    // ──────────────────────────────────────────────────────────────────

    /**
     * Verifies the position consistency invariant: the subscriber position
     * and the view checkpoint position must be equal after an atomic write.
     * This is the invariant that crash recovery depends on.
     */
    @Test
    @DisplayName("subscriber position equals view checkpoint position — the crash-recovery invariant")
    void writeAtomicCheckpoint_positionConsistency() {
        writer.writeAtomicCheckpoint("state-projection", 100L,
                "entity_state", new byte[]{10});

        long subscriberPosition = checkpointStore.readCheckpoint("state-projection");
        Optional<CheckpointRecord> viewRecord =
                viewCheckpointStore.readLatestCheckpoint("entity_state");

        assertThat(viewRecord).isPresent();
        assertThat(subscriberPosition)
                .as("subscriber and view positions must be equal")
                .isEqualTo(viewRecord.get().position())
                .isEqualTo(100L);
    }

    // ──────────────────────────────────────────────────────────────────
    // Test 3: Overwrite atomicity
    // ──────────────────────────────────────────────────────────────────

    /**
     * Verifies that a second atomic write fully overwrites both tables —
     * the subscriber position and view data must reflect the second write,
     * not a mix of first and second.
     */
    @Test
    @DisplayName("second atomic write fully overwrites both tables")
    void writeAtomicCheckpoint_overwriteAtomicity() {
        byte[] bytesA = {10, 20, 30};
        byte[] bytesB = {40, 50, 60};

        writer.writeAtomicCheckpoint("state-projection", 100L,
                "entity_state", bytesA);
        writer.writeAtomicCheckpoint("state-projection", 200L,
                "entity_state", bytesB);

        long subscriberPosition = checkpointStore.readCheckpoint("state-projection");
        assertThat(subscriberPosition).isEqualTo(200L);

        Optional<CheckpointRecord> viewRecord =
                viewCheckpointStore.readLatestCheckpoint("entity_state");
        assertThat(viewRecord).isPresent();
        assertThat(viewRecord.get().position()).isEqualTo(200L);
        assertThat(viewRecord.get().data()).isEqualTo(bytesB);
    }

    // ──────────────────────────────────────────────────────────────────
    // Test 4: Rollback on failure
    // ──────────────────────────────────────────────────────────────────

    /**
     * Validates that if the transaction fails, NEITHER table is updated
     * beyond the established baseline.
     *
     * <p>Strategy: write a successful atomic checkpoint at position 50
     * (establishes baseline). Then drop the {@code view_checkpoints} table
     * to force a SQL failure on the second statement of a subsequent atomic
     * write at position 100. Read both stores — both should still reflect
     * position 50.</p>
     *
     * <p>[REVIEW] The rollback test uses a schema corruption strategy (DROP
     * TABLE) rather than a connection wrapper/spy. This is practical because
     * {@code INSERT OR REPLACE} with valid parameters never fails on valid
     * schema — we need external corruption to trigger a mid-transaction
     * failure. After verification, we recreate the table to allow the read
     * operations to succeed.</p>
     */
    @Test
    @DisplayName("transaction failure rolls back both tables — neither is updated")
    void writeAtomicCheckpoint_rollbackOnFailure() {
        // Establish baseline at position 50
        writer.writeAtomicCheckpoint("state-projection", 50L,
                "entity_state", new byte[]{1, 2, 3});

        // Drop the view_checkpoints table to force the second statement to fail.
        // This must run on the write thread to avoid thread-safety issues.
        databaseExecutor.writeCoordinator().submit(WritePriority.STATE_PROJECTION, () -> {
            databaseExecutor.writeConnection().createStatement()
                    .execute("DROP TABLE view_checkpoints");
            return null;
        });

        // Attempt an atomic write at position 100 — the view checkpoint
        // INSERT will fail because the table no longer exists.
        try {
            writer.writeAtomicCheckpoint("state-projection", 100L,
                    "entity_state", new byte[]{4, 5, 6});
        } catch (RuntimeException expected) {
            // Expected — the DROP TABLE causes the INSERT to fail
        }

        // Recreate the view_checkpoints table so readLatestCheckpoint works.
        // The subscriber_checkpoints table was not dropped, so its data is
        // still the baseline IF the rollback succeeded correctly.
        databaseExecutor.writeCoordinator().submit(WritePriority.STATE_PROJECTION, () -> {
            databaseExecutor.writeConnection().createStatement().execute(
                    "CREATE TABLE IF NOT EXISTS view_checkpoints ("
                    + "view_name TEXT PRIMARY KEY, "
                    + "position INTEGER NOT NULL, "
                    + "data BLOB NOT NULL, "
                    + "updated_at INTEGER NOT NULL)");
            return null;
        });

        // Subscriber checkpoint should still be at the baseline (50), not
        // advanced to 100. The rollback undid the subscriber INSERT.
        long subscriberPosition = checkpointStore.readCheckpoint("state-projection");
        assertThat(subscriberPosition)
                .as("subscriber position must remain at baseline after rollback")
                .isEqualTo(50L);

        // View checkpoint table was dropped and recreated — it should be
        // empty (the baseline data was lost with the DROP, and the new
        // write was rolled back).
        Optional<CheckpointRecord> viewRecord =
                viewCheckpointStore.readLatestCheckpoint("entity_state");
        assertThat(viewRecord)
                .as("view checkpoint should be absent after table drop + rollback")
                .isEmpty();
    }

    // ──────────────────────────────────────────────────────────────────
    // Tests 5–8: Parameter validation
    // ──────────────────────────────────────────────────────────────────

    /**
     * Null subscriberId is rejected with {@link NullPointerException} on the
     * caller's thread. Neither table is modified.
     */
    @Test
    @DisplayName("null subscriberId throws NullPointerException")
    void writeAtomicCheckpoint_nullSubscriberId_throwsNPE() {
        assertThatThrownBy(() ->
                writer.writeAtomicCheckpoint(null, 10L,
                        "entity_state", new byte[]{1}))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("subscriberId");
    }

    /**
     * Null viewName is rejected with {@link NullPointerException} on the
     * caller's thread.
     */
    @Test
    @DisplayName("null viewName throws NullPointerException")
    void writeAtomicCheckpoint_nullViewName_throwsNPE() {
        assertThatThrownBy(() ->
                writer.writeAtomicCheckpoint("state-projection", 10L,
                        null, new byte[]{1}))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("viewName");
    }

    /**
     * Null viewData is rejected with {@link NullPointerException} on the
     * caller's thread.
     */
    @Test
    @DisplayName("null viewData throws NullPointerException")
    void writeAtomicCheckpoint_nullViewData_throwsNPE() {
        assertThatThrownBy(() ->
                writer.writeAtomicCheckpoint("state-projection", 10L,
                        "entity_state", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("viewData");
    }

    /**
     * Negative position is rejected with {@link IllegalArgumentException}
     * on the caller's thread.
     */
    @Test
    @DisplayName("negative position throws IllegalArgumentException")
    void writeAtomicCheckpoint_negativePosition_throwsIAE() {
        assertThatThrownBy(() ->
                writer.writeAtomicCheckpoint("state-projection", -1L,
                        "entity_state", new byte[]{1}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("position");
    }

    // ──────────────────────────────────────────────────────────────────
    // Test 9: Position zero is valid
    // ──────────────────────────────────────────────────────────────────

    /**
     * Position 0 is a valid checkpoint value — "initialized but no events
     * processed." Verifies both tables store 0 correctly.
     */
    @Test
    @DisplayName("position 0 is valid and stored in both tables")
    void writeAtomicCheckpoint_positionZero_isValid() {
        writer.writeAtomicCheckpoint("state-projection", 0L,
                "entity_state", new byte[]{99});

        assertThat(checkpointStore.readCheckpoint("state-projection"))
                .isEqualTo(0L);

        Optional<CheckpointRecord> viewRecord =
                viewCheckpointStore.readLatestCheckpoint("entity_state");
        assertThat(viewRecord).isPresent();
        assertThat(viewRecord.get().position()).isEqualTo(0L);
        assertThat(viewRecord.get().data()).isEqualTo(new byte[]{99});
    }

    // ──────────────────────────────────────────────────────────────────
    // Test 10: autoCommit restored after success
    // ──────────────────────────────────────────────────────────────────

    /**
     * After a successful atomic write, the write connection's autoCommit must
     * be restored. Validates this by performing a subsequent individual write
     * via {@link SqliteCheckpointStore#writeCheckpoint} and reading it back.
     * If autoCommit was NOT restored, the individual write would silently
     * not commit (it would sit in a never-committed transaction).
     */
    @Test
    @DisplayName("autoCommit is restored after successful atomic write")
    void writeAtomicCheckpoint_autoCommitRestoredAfterSuccess() {
        // Perform an atomic write
        writer.writeAtomicCheckpoint("state-projection", 50L,
                "entity_state", new byte[]{1});

        // Now do a plain individual write via the regular CheckpointStore
        checkpointStore.writeCheckpoint("other-subscriber", 75L);

        // If autoCommit was not restored, this write never committed
        long position = checkpointStore.readCheckpoint("other-subscriber");
        assertThat(position)
                .as("individual write must commit — autoCommit was restored")
                .isEqualTo(75L);
    }

    // ──────────────────────────────────────────────────────────────────
    // Test 11: Large viewData round-trips
    // ──────────────────────────────────────────────────────────────────

    /**
     * Writes ~75 KB of viewData (realistic State Store checkpoint size) and
     * reads it back via {@link SqliteViewCheckpointStore}. Verifies
     * byte-for-byte equality.
     */
    @Test
    @DisplayName("large viewData (~75 KB) round-trips byte-for-byte")
    void writeAtomicCheckpoint_largeViewData_roundTrips() {
        // 75 KB of patterned data
        byte[] largeData = new byte[75 * 1024];
        for (int i = 0; i < largeData.length; i++) {
            largeData[i] = (byte) (i % 251); // prime modulus for non-trivial pattern
        }

        writer.writeAtomicCheckpoint("state-projection", 999L,
                "entity_state", largeData);

        Optional<CheckpointRecord> viewRecord =
                viewCheckpointStore.readLatestCheckpoint("entity_state");
        assertThat(viewRecord).isPresent();
        assertThat(viewRecord.get().position()).isEqualTo(999L);
        assertThat(viewRecord.get().data())
                .as("75 KB viewData must round-trip byte-for-byte")
                .isEqualTo(largeData);
    }
}
