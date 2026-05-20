/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import com.homesynapse.event.bus.DeadLetter;
import com.homesynapse.platform.identity.Ulid;
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

/**
 * Tests for {@link AtomicCheckpointWriter#writeAtomicCheckpointWithDlqPark} —
 * the three-way atomic write extension landed in M3.5b (AMD-36 atomicity).
 *
 * <p>Lives in a sibling test class to the M2.8 {@code AtomicCheckpointWriterTest}
 * because the DLQ extension requires V002 (the
 * {@code subscriber_dead_letters} table) in the migration manifest, whereas
 * the original test only enrolls V001.</p>
 */
@DisplayName("AtomicCheckpointWriter — three-way DLQ park extension")
final class AtomicCheckpointWriterDlqTest {

    private static final String EVENTS_MIGRATION_PATH = "db/migration/events";

    private static final List<String> EVENTS_MIGRATION_FILES = List.of(
            "V001__initial_event_store_schema.sql",
            "V002__subscriber_dead_letter_queue.sql",
            "V003__add_snapshots_and_drop_redundant_index.sql",
            "V004__dlq_operational_indices.sql");

    private static final DeploymentProfile PROFILE = DeploymentProfile.HOME;

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-01-15T12:00:00Z"), ZoneOffset.UTC);

    private static final Ulid EVENT_ID = new Ulid(
            0x01__00_00_00_00_00_00_00L, 0x00_00_00_00_00_00_00_01L);

    @TempDir
    Path tempDir;

    private DatabaseExecutor databaseExecutor;
    private AtomicCheckpointWriter writer;
    private SqliteCheckpointStore checkpointStore;
    private SqliteViewCheckpointStore viewCheckpointStore;
    private SqliteDeadLetterStore dlqStore;

    /** Creates a new test instance. */
    AtomicCheckpointWriterDlqTest() {
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
        dlqStore = new SqliteDeadLetterStore(databaseExecutor);
    }

    @AfterEach
    void tearDown() {
        if (checkpointStore != null) {
            checkpointStore.clearThreadLocalForTesting();
        }
        if (viewCheckpointStore != null) {
            viewCheckpointStore.clearThreadLocalForTesting();
        }
        if (dlqStore != null) {
            dlqStore.clearThreadLocalForTesting();
        }
        if (databaseExecutor != null) {
            databaseExecutor.shutdown();
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Happy path
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("three-way write updates all three tables atomically")
    void writeAtomicWithDlqPark_allTablesUpdated() {
        byte[] viewData = {1, 2, 3, 4};
        DeadLetter dl = newDeadLetter(100L, 1);

        writer.writeAtomicCheckpointWithDlqPark(
                "state-projection", 100L, "entity_state", viewData, dl);

        // Subscriber checkpoint advanced
        assertThat(checkpointStore.readCheckpoint("state-projection")).isEqualTo(100L);

        // View checkpoint advanced with the supplied bytes
        Optional<CheckpointRecord> view = viewCheckpointStore.readLatestCheckpoint("entity_state");
        assertThat(view).isPresent();
        assertThat(view.get().position()).isEqualTo(100L);
        assertThat(view.get().data()).isEqualTo(viewData);

        // DLQ row inserted
        Optional<DeadLetter> parked = dlqStore.findByPosition("state-projection", 100L);
        assertThat(parked).isPresent();
        assertThat(parked.get().attemptCount()).isEqualTo(1);
        assertThat(parked.get().causeClass()).isEqualTo(dl.causeClass());
    }

    // ──────────────────────────────────────────────────────────────────
    // Rollback semantics
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("transaction failure rolls back all three tables")
    void writeAtomicWithDlqPark_rollbackOnFailure() {
        // Baseline: a successful three-way write at position 50.
        writer.writeAtomicCheckpointWithDlqPark(
                "state-projection", 50L, "entity_state", new byte[]{1},
                newDeadLetter(50L, 1));

        // Drop the DLQ table so the third statement fails. The DROP itself
        // must run on the write thread.
        databaseExecutor.writeCoordinator().submit(WritePriority.STATE_PROJECTION, () -> {
            databaseExecutor.writeConnection().createStatement()
                    .execute("DROP TABLE subscriber_dead_letters");
            return null;
        });

        // Attempt a second three-way write at position 100. The DLQ INSERT
        // fails — the subscriber checkpoint and view checkpoint advances
        // must be rolled back.
        try {
            writer.writeAtomicCheckpointWithDlqPark(
                    "state-projection", 100L, "entity_state", new byte[]{2, 2},
                    newDeadLetter(100L, 1));
        } catch (RuntimeException expected) {
            // Expected — the DROP causes the third INSERT to fail.
        }

        // Recreate the DLQ table so the verification reads succeed.
        databaseExecutor.writeCoordinator().submit(WritePriority.STATE_PROJECTION, () -> {
            databaseExecutor.writeConnection().createStatement().execute("""
                    CREATE TABLE IF NOT EXISTS subscriber_dead_letters (
                        dlq_id            INTEGER PRIMARY KEY AUTOINCREMENT,
                        subscriber_id     TEXT    NOT NULL,
                        sequence_key      TEXT    NOT NULL,
                        event_position    INTEGER NOT NULL,
                        event_id          BLOB(16) NOT NULL,
                        cause_class       TEXT    NOT NULL,
                        cause_message     TEXT    NOT NULL,
                        attempt_count     INTEGER NOT NULL DEFAULT 1,
                        first_seen_at     INTEGER NOT NULL,
                        last_attempt_at   INTEGER NOT NULL,
                        diagnostics       TEXT,
                        UNIQUE(subscriber_id, event_position)
                    )""");
            return null;
        });

        // Subscriber checkpoint must remain at baseline (50, not 100).
        assertThat(checkpointStore.readCheckpoint("state-projection")).isEqualTo(50L);

        // View checkpoint must remain at baseline (position 50, bytes {1}).
        Optional<CheckpointRecord> view = viewCheckpointStore.readLatestCheckpoint("entity_state");
        assertThat(view).isPresent();
        assertThat(view.get().position()).isEqualTo(50L);
        assertThat(view.get().data()).isEqualTo(new byte[]{1});

        // DLQ — the recreated table is empty. The rollback prevented the
        // position-100 row from being committed; the baseline position-50 row
        // was lost with the DROP, not with the rollback.
        assertThat(dlqStore.countBySubscriber("state-projection")).isEqualTo(0);
    }

    // ──────────────────────────────────────────────────────────────────
    // Upsert semantics
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("re-parking the same DLQ event upserts (no duplicate row)")
    void writeAtomicWithDlqPark_upsertExistingDlqEntry() {
        // First three-way write: attempt 1.
        writer.writeAtomicCheckpointWithDlqPark(
                "state-projection", 100L, "entity_state", new byte[]{1, 2},
                newDeadLetter(100L, 1));

        // Second three-way write for the same event position: attempt 4.
        // Position and view data update normally; the DLQ row upserts.
        writer.writeAtomicCheckpointWithDlqPark(
                "state-projection", 200L, "entity_state", new byte[]{3, 4},
                newDeadLetter(100L, 4));

        // Still exactly one DLQ row for this subscriber.
        assertThat(dlqStore.countBySubscriber("state-projection")).isEqualTo(1);

        // attempt_count reflects the second park's value.
        DeadLetter parked = dlqStore.findByPosition("state-projection", 100L).orElseThrow();
        assertThat(parked.attemptCount()).isEqualTo(4);

        // Subscriber checkpoint advanced to the latest position.
        assertThat(checkpointStore.readCheckpoint("state-projection")).isEqualTo(200L);
    }

    // ──────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────

    private static DeadLetter newDeadLetter(long eventPosition, int attemptCount) {
        Instant now = Instant.parse("2026-01-15T12:00:00Z");
        return new DeadLetter(
                DeadLetter.UNASSIGNED_DLQ_ID,
                "state-projection",
                "sub:dev:1",
                eventPosition,
                EVENT_ID,
                "java.lang.RuntimeException",
                "boom",
                attemptCount,
                now,
                now,
                null);
    }
}
