/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import com.homesynapse.event.bus.DeadLetter;
import com.homesynapse.event.bus.test.DeadLetterStoreContractTest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * Wires {@link SqliteDeadLetterStore} into the abstract
 * {@link DeadLetterStoreContractTest} from {@code core/event-bus}'s
 * testFixtures source set.
 *
 * <p>Mirrors the established M2.5/M2.6/M2.7 pattern: {@code @TempDir} provides
 * a fresh database file per test method; {@link #resetStore()} (called from
 * the parent's {@code @BeforeEach}) opens the {@link DatabaseExecutor}, runs
 * the V001/V002/V004 migrations, and constructs the store; {@code @AfterEach
 * tearDown()} shuts the executor down so the JDBC driver releases the
 * temp-dir database file before JUnit deletes it.</p>
 *
 * <p>V004 (DLQ operational indices) is included so production-shaped queries
 * benefit from the same indices in tests.</p>
 */
@DisplayName("SqliteDeadLetterStore — contract against the event-bus fixture")
final class SqliteDeadLetterStoreContractTest extends DeadLetterStoreContractTest {

    private static final String EVENTS_MIGRATION_PATH = "db/migration/events";

    private static final List<String> EVENTS_MIGRATION_FILES = List.of(
            "V001__initial_event_store_schema.sql",
            "V002__subscriber_dead_letter_queue.sql",
            "V003__add_snapshots_and_drop_redundant_index.sql",
            "V004__dlq_operational_indices.sql");

    private static final int READ_THREAD_COUNT = 2;

    private static final Instant FIXED_INSTANT =
            Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock FIXED_CLOCK =
            Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

    @TempDir
    Path tempDir;

    private DatabaseExecutor dbExecutor;
    private SqliteDeadLetterStore store;

    /** Creates a new test instance. */
    SqliteDeadLetterStoreContractTest() {
        // Explicit no-arg constructor for -Xlint:all -Werror builds.
    }

    @Override
    protected void resetStore() {
        shutdownQuietly();
        Path dbPath = tempDir.resolve("events.db");

        dbExecutor = new DatabaseExecutor(READ_THREAD_COUNT, FIXED_CLOCK);
        dbExecutor.start(
                dbPath,
                EVENTS_MIGRATION_PATH,
                EVENTS_MIGRATION_FILES,
                MigrationConfig.freshInstall());

        store = new SqliteDeadLetterStore(dbExecutor);
    }

    @Override
    protected void park(DeadLetter deadLetter) {
        store.park(deadLetter);
    }

    @Override
    protected List<DeadLetter> findBySubscriber(String subscriberId) {
        return store.findBySubscriber(subscriberId);
    }

    @Override
    protected Optional<DeadLetter> findByPosition(String subscriberId, long eventPosition) {
        return store.findByPosition(subscriberId, eventPosition);
    }

    @Override
    protected int countBySubscriber(String subscriberId) {
        return store.countBySubscriber(subscriberId);
    }

    @Override
    protected void parkNullRejected() {
        store.park(null);
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
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
                // Tear-down failures must not mask the test's primary failure.
            }
            dbExecutor = null;
        }
    }
}
