/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import com.homesynapse.state.CheckpointRecord;
import com.homesynapse.state.ViewCheckpointStore;
import com.homesynapse.state.test.ViewCheckpointStoreContractTest;

import org.junit.jupiter.api.AfterEach;
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
 * Wires {@link SqliteViewCheckpointStore} into the abstract
 * {@link ViewCheckpointStoreContractTest} so that the SQLite-backed view
 * checkpoint store passes the same 10-method behavioral contract as
 * {@code InMemoryViewCheckpointStore}. Parity between the two implementations
 * is what validates the interface design (Doc 04 §3.12).
 *
 * <p>This subclass provides the concrete persistence wiring — a real
 * file-backed SQLite database under a JUnit {@link TempDir}, a
 * {@link DatabaseExecutor} with its single-writer / bounded-reader platform
 * thread model, and a freshly migrated {@code view_checkpoints} table produced
 * by the V001 migration — plus three SQLite-specific tests that exercise
 * behavior the in-memory fixture cannot reach: position-zero validity, negative
 * position rejection, and microsecond timestamp round-tripping through
 * {@link TimeConversion}.</p>
 *
 * <p><strong>Test-isolation strategy.</strong> Mirrors the M2.6
 * {@code SqliteCheckpointStoreTest} pattern: the parent contract test invokes
 * {@link #resetStore()} from its own {@code @BeforeEach}, which JUnit 5 runs
 * before any subclass {@code @BeforeEach} methods. We exploit that ordering by
 * performing <em>all</em> per-test initialization inside {@link #resetStore()}
 * itself — the method opens a fresh database under the per-test {@link TempDir}
 * (which JUnit 5 injects before any {@code @BeforeEach} runs), starts the
 * {@link DatabaseExecutor} (which runs the V001 migration and therefore creates
 * the {@code view_checkpoints} table), and constructs the
 * {@link SqliteViewCheckpointStore}. The {@code @AfterEach} tear-down shuts the
 * executor down cleanly between tests so the JDBC driver releases the
 * temp-dir database file before JUnit deletes the temp directory.</p>
 *
 * <p>The store is constructed with a {@link Clock#fixed fixed clock} — the
 * {@code NO_DIRECT_TIME_ACCESS} ArchUnit rule forbids {@code Clock.systemUTC()}
 * outside {@code com.homesynapse.app..} and {@code com.homesynapse.platform..},
 * and the contract test does not depend on wall-clock advancement. The
 * {@code writtenAt} column stored on every checkpoint write therefore contains
 * a deterministic Unix-microsecond value derived from {@link #FIXED_INSTANT}.</p>
 *
 * @see ViewCheckpointStoreContractTest
 * @see SqliteViewCheckpointStore
 * @see SqliteCheckpointStoreTest
 */
@DisplayName("SqliteViewCheckpointStore — contract against the state-store fixture")
final class SqliteViewCheckpointStoreTest extends ViewCheckpointStoreContractTest {

    /** Classpath directory holding the events-database migration scripts. */
    private static final String EVENTS_MIGRATION_PATH = "db/migration/events";

    /** The ordered list of migration files the executor should apply. */
    private static final List<String> EVENTS_MIGRATION_FILES = List.of(
            "V001__initial_event_store_schema.sql");

    /** Number of read executor threads — two is enough to exercise round-robin. */
    private static final int READ_THREAD_COUNT = 2;

    /**
     * Fixed instant for the test clock. Matches the convention used by
     * {@code SqliteCheckpointStoreTest} and {@code SqliteEventStoreTest} so
     * every persistence-module contract-test subclass shares a single
     * deterministic epoch.
     */
    private static final Instant FIXED_INSTANT =
            Instant.parse("2026-01-01T00:00:00Z");

    /**
     * Fixed clock shared across all tests. The {@code NO_DIRECT_TIME_ACCESS}
     * ArchUnit rule forbids {@code Clock.systemUTC()} outside the assembly
     * and platform modules, so this subclass uses a fixed clock instead.
     */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

    @TempDir
    Path tempDir;

    private DatabaseExecutor dbExecutor;
    private SqliteViewCheckpointStore store;

    /** Creates a new test instance. */
    SqliteViewCheckpointStoreTest() {
        // Explicit no-arg constructor for -Xlint:all -Werror builds.
    }

    // ──────────────────────────────────────────────────────────────────
    // ViewCheckpointStoreContractTest wiring
    // ──────────────────────────────────────────────────────────────────

    @Override
    protected ViewCheckpointStore store() {
        return store;
    }

    @Override
    protected Clock clock() {
        return FIXED_CLOCK;
    }

    /**
     * Builds a fresh SQLite database, executor, and store for each test.
     * Called from the parent class's {@code @BeforeEach} — running before
     * any subclass {@code @BeforeEach}, so this is where <em>all</em>
     * per-test initialization lives.
     */
    @Override
    protected void resetStore() {
        // Shut the previous executor down if an earlier invocation in the
        // same test already created one. This would not normally happen
        // (contract tests call resetStore() at most once per test), but it
        // guards against future subclass test methods that invoke the hook
        // directly.
        shutdownQuietly();

        // @TempDir provides a fresh directory per test method, so a stable
        // filename is sufficient — no need for a unique suffix (and the
        // NO_DIRECT_TIME_ACCESS arch rule forbids System.nanoTime() here).
        Path dbPath = tempDir.resolve("events.db");

        dbExecutor = new DatabaseExecutor(READ_THREAD_COUNT, FIXED_CLOCK);
        dbExecutor.start(
                dbPath,
                EVENTS_MIGRATION_PATH,
                EVENTS_MIGRATION_FILES,
                MigrationConfig.freshInstall());

        store = new SqliteViewCheckpointStore(dbExecutor, FIXED_CLOCK);
    }

    /**
     * Tears the per-test executor down so the JDBC driver releases the
     * temp-dir database file before JUnit deletes the temp directory.
     */
    @AfterEach
    void tearDown() {
        if (store != null) {
            // Clear any ThreadLocal binding the store may have left on the
            // current thread — harmless on the main test thread since the
            // store is about to be discarded, but keeps the hygiene explicit.
            store.clearThreadLocalForTesting();
        }
        shutdownQuietly();
        store = null;
    }

    private void shutdownQuietly() {
        if (dbExecutor != null) {
            try {
                dbExecutor.shutdown();
            } catch (RuntimeException ignore) {
                // Shutdown failures in tear-down must not mask the test's
                // primary assertion failure; swallow intentionally.
            }
            dbExecutor = null;
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // SQLite-specific tests (beyond the 10-method contract)
    // ──────────────────────────────────────────────────────────────────

    /**
     * Verifies that {@code position = 0} is a valid checkpoint value. The
     * contract test suite uses positions 5, 10, 20, 42, 50, 100, and 1000 but
     * never zero — yet position zero is the semantically-important "this view
     * has been initialized but has processed zero events" checkpoint. The
     * {@code INSERT OR REPLACE} statement must accept it and the round-trip
     * must preserve the literal value, not treat it as a sentinel.
     */
    @Test
    @DisplayName("position = 0 is a valid checkpoint value")
    void writeCheckpoint_positionZero_roundTrips() {
        store.writeCheckpoint("entity_state", 0L, new byte[]{7});

        Optional<CheckpointRecord> result = store.readLatestCheckpoint("entity_state");

        assertThat(result).isPresent();
        assertThat(result.get().position()).isEqualTo(0L);
        assertThat(result.get().data()).isEqualTo(new byte[]{7});
    }

    /**
     * Verifies that negative positions are rejected with
     * {@link IllegalArgumentException} on the caller's thread — before the
     * operation is submitted to the write coordinator. Negative positions
     * cannot represent a valid global event log position. The contract suite
     * does not test position validation; this guard matches the pattern
     * established by {@link SqliteCheckpointStore#writeCheckpoint}.
     */
    @Test
    @DisplayName("negative position is rejected with IllegalArgumentException")
    void writeCheckpoint_negativePosition_throwsIAE() {
        assertThatThrownBy(() ->
                store.writeCheckpoint("entity_state", -1, new byte[]{1}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("position");
    }

    /**
     * Verifies that the {@code writtenAt} field round-trips through the
     * {@link TimeConversion#toMicros}/{@link TimeConversion#fromMicros} pair
     * with microsecond precision preserved. The contract test only verifies
     * equality against {@code clock().instant()} with a fixed clock aligned
     * to the epoch second — which does not exercise the microsecond pathway.
     * This test uses a fixed instant with a non-zero microsecond component to
     * confirm the conversion is lossless at the persistence boundary.
     *
     * <p>Uses a dedicated executor and store rather than the shared fixture
     * so the clock is different from {@link #FIXED_CLOCK} without leaking
     * state into subsequent tests.</p>
     */
    @Test
    @DisplayName("writtenAt preserves microsecond precision through TimeConversion")
    void writeCheckpoint_writtenAt_preservesMicrosecondPrecision() {
        // 123,456 microseconds past the epoch second — non-zero in both
        // the microsecond column and the sub-second nanosecond range.
        Instant microInstant = Instant.parse("2026-04-07T12:00:00Z").plusNanos(123_456_000L);
        Clock microClock = Clock.fixed(microInstant, ZoneOffset.UTC);

        // Fresh isolated dependencies — do not disturb the shared fixture.
        DatabaseExecutor isolatedExecutor = new DatabaseExecutor(READ_THREAD_COUNT, microClock);
        try {
            isolatedExecutor.start(
                    tempDir.resolve("micro-precision.db"),
                    EVENTS_MIGRATION_PATH,
                    EVENTS_MIGRATION_FILES,
                    MigrationConfig.freshInstall());

            SqliteViewCheckpointStore microStore =
                    new SqliteViewCheckpointStore(isolatedExecutor, microClock);
            try {
                microStore.writeCheckpoint("entity_state", 1L, new byte[]{1});

                Optional<CheckpointRecord> result =
                        microStore.readLatestCheckpoint("entity_state");

                assertThat(result).isPresent();
                assertThat(result.get().writtenAt()).isEqualTo(microInstant);
            } finally {
                microStore.clearThreadLocalForTesting();
            }
        } finally {
            isolatedExecutor.shutdown();
        }
    }
}
