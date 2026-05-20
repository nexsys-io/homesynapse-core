/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import com.homesynapse.event.bus.CheckpointStore;
import com.homesynapse.event.bus.test.CheckpointStoreContractTest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Wires {@link SqliteCheckpointStore} into the abstract
 * {@link CheckpointStoreContractTest} so that the SQLite-backed checkpoint
 * store passes the same 9-method behavioral contract as
 * {@code InMemoryCheckpointStore}. Parity between the two implementations
 * is what validates the interface design.
 *
 * <p>This subclass has no additional test methods — it exists solely to
 * provide the concrete persistence wiring: a real file-backed SQLite
 * database under a JUnit {@link TempDir}, a {@link DatabaseExecutor} with
 * its single-writer / bounded-reader platform thread model, and a freshly
 * migrated {@code subscriber_checkpoints} table produced by the V001
 * migration.</p>
 *
 * <p><strong>Test-isolation strategy.</strong> Mirrors the M2.5
 * {@code SqliteEventStoreTest} pattern: the parent contract test invokes
 * {@link #resetStore()} from its own {@code @BeforeEach}, which JUnit 5 runs
 * before any subclass {@code @BeforeEach} methods. We exploit that ordering by
 * performing <em>all</em> per-test initialization inside {@link #resetStore()}
 * itself — the method opens a fresh database under the per-test {@link TempDir}
 * (which JUnit 5 injects before any {@code @BeforeEach} runs), starts the
 * {@link DatabaseExecutor} (which runs the V001 migration and therefore creates
 * the {@code subscriber_checkpoints} table), and constructs the
 * {@link SqliteCheckpointStore}. The {@code @AfterEach} tear-down shuts the
 * executor down cleanly between tests so the JDBC driver releases the
 * temp-dir database file before JUnit deletes the temp directory.</p>
 *
 * <p>The store is constructed with a {@link Clock#fixed fixed clock} — the
 * {@code NO_DIRECT_TIME_ACCESS} ArchUnit rule forbids {@code Clock.systemUTC()}
 * outside {@code com.homesynapse.app..} and {@code com.homesynapse.platform..},
 * and the contract test does not depend on wall-clock advancement. The
 * {@code last_updated} column stored on every checkpoint write therefore
 * contains a deterministic Unix-microsecond value derived from
 * {@link #FIXED_INSTANT}.</p>
 *
 * @see CheckpointStoreContractTest
 * @see SqliteCheckpointStore
 */
@DisplayName("SqliteCheckpointStore — contract against the event-bus fixture")
final class SqliteCheckpointStoreTest extends CheckpointStoreContractTest {

    /** Classpath directory holding the events-database migration scripts. */
    private static final String EVENTS_MIGRATION_PATH = "db/migration/events";

    /** The ordered list of migration files the executor should apply. */
    private static final List<String> EVENTS_MIGRATION_FILES = List.of(
            "V001__initial_event_store_schema.sql");

    /** Deployment profile (HOME — 2 read threads, enough to exercise round-robin). */
    private static final DeploymentProfile PROFILE = DeploymentProfile.HOME;

    /**
     * Fixed instant for the test clock. Matches the convention used by
     * {@code SqliteEventStoreTest} so every persistence-module contract-test
     * subclass shares a single deterministic epoch.
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
    private SqliteCheckpointStore store;

    /** Creates a new test instance. */
    SqliteCheckpointStoreTest() {
        // Explicit no-arg constructor for -Xlint:all -Werror builds.
    }

    // ──────────────────────────────────────────────────────────────────
    // CheckpointStoreContractTest wiring
    // ──────────────────────────────────────────────────────────────────

    @Override
    protected CheckpointStore store() {
        return store;
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

        dbExecutor = new DatabaseExecutor(PROFILE, FIXED_CLOCK);
        dbExecutor.start(
                dbPath,
                EVENTS_MIGRATION_PATH,
                EVENTS_MIGRATION_FILES,
                MigrationConfig.freshInstall());

        store = new SqliteCheckpointStore(dbExecutor, FIXED_CLOCK);
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
}
