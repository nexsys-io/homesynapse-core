/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homesynapse.event.DomainEvent;
import com.homesynapse.event.EventPublisher;
import com.homesynapse.event.EventStore;
import com.homesynapse.event.test.EventStoreContractTest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Wires {@link SqliteEventStore} into the abstract
 * {@link EventStoreContractTest} contract.
 *
 * <p>This subclass has no additional test methods — it exists solely to
 * provide the concrete persistence wiring (a real file-backed SQLite database
 * under a JUnit {@link TempDir}, a {@link DatabaseExecutor} with its
 * single-writer / bounded-reader thread model, a Jackson-backed
 * {@link EventPayloadCodec}, and the {@link SqliteEventStore} itself) so that
 * all 27 inherited contract tests exercise the production SQLite round trip.
 * If the inherited tests pass, the SQLite event store satisfies the full
 * publisher + store behavioral contract defined by Doc 01 §4 and
 * §8.</p>
 *
 * <p><strong>Test-isolation strategy.</strong> The abstract parent invokes
 * {@link #resetStore()} from its own {@code @BeforeEach}, which JUnit 5 runs
 * before any subclass {@code @BeforeEach} methods. We exploit that ordering by
 * performing <em>all</em> per-test initialization inside {@link #resetStore()}
 * itself — the method opens a fresh database under the per-test {@link TempDir}
 * (which JUnit 5 injects before any {@code @BeforeEach} runs), starts the
 * {@link DatabaseExecutor}, runs the V001 migration, and constructs the
 * {@link SqliteEventStore}. The {@code @AfterEach} tear-down shuts the
 * executor down cleanly between tests. This avoids the alternative of a
 * lazy-accessor pattern that would race with the parent's setup ordering.</p>
 *
 * <p><strong>Event type registry.</strong> The registry is seeded with the
 * full production event set ({@link AllEventClasses#ALL_EVENTS}) plus the
 * contract test's fixture payload
 * ({@link EventStoreContractTest.TestPayload}) so that Jackson warmup can
 * produce a writer and reader for the fixture type. The contract test also
 * publishes drafts with unregistered {@code eventType} strings (e.g.
 * {@code "event_1"}, {@code "state_changed"}); those paths deliberately
 * exercise the {@code DegradedEvent} fallback decode and are not expected to
 * resolve to the fixture payload on read.</p>
 *
 * <p>The store is constructed with a {@link Clock#fixed fixed clock} — the
 * {@code NO_DIRECT_TIME_ACCESS} ArchUnit rule forbids {@code Clock.systemUTC()}
 * outside {@code com.homesynapse.app..} and {@code com.homesynapse.platform..},
 * and contract-test behavior does not depend on wall-clock advancement anyway.
 * The production {@code UlidFactory} handles sub-millisecond ULID monotonicity
 * even when the clock returns a constant instant, so every {@code publish()}
 * still produces a distinct, strictly-increasing {@code eventId}.</p>
 *
 * @see EventStoreContractTest
 * @see SqliteEventStore
 */
@DisplayName("SqliteEventStore — contract against the event-model fixture")
final class SqliteEventStoreTest extends EventStoreContractTest {

    /** Classpath directory holding the events-database migration scripts. */
    private static final String EVENTS_MIGRATION_PATH = "db/migration/events";

    /** The ordered list of migration files the executor should apply. */
    private static final List<String> EVENTS_MIGRATION_FILES = List.of(
            "V001__initial_event_store_schema.sql");

    /** Number of read executor threads — two is enough to exercise round-robin. */
    private static final int READ_THREAD_COUNT = 2;

    /**
     * Fixed instant for the test clock. Matches the convention used by
     * {@code InMemoryEventStoreTest} so both contract-test subclasses share a
     * single deterministic epoch.
     */
    private static final Instant FIXED_INSTANT =
            Instant.parse("2026-01-01T00:00:00Z");

    /**
     * Fixed clock shared across all tests. The {@code NO_DIRECT_TIME_ACCESS}
     * ArchUnit rule forbids {@code Clock.systemUTC()} outside the assembly
     * and platform modules, so this subclass uses a fixed clock instead —
     * the store's behavior under test does not depend on wall-clock
     * advancement.
     */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

    @TempDir
    Path tempDir;

    private DatabaseExecutor dbExecutor;
    private SqliteEventStore store;

    /** Creates a new test instance. */
    SqliteEventStoreTest() {
        // Explicit no-arg constructor for -Xlint:all -Werror builds.
    }

    // ──────────────────────────────────────────────────────────────────
    // EventStoreContractTest wiring
    // ──────────────────────────────────────────────────────────────────

    @Override
    protected EventPublisher publisher() {
        return store;
    }

    @Override
    protected EventStore store() {
        return store;
    }

    /**
     * Builds a fresh SQLite database, executor, codec, and store for each
     * test. Called from the parent class's {@code @BeforeEach} — running
     * before any subclass {@code @BeforeEach}, so this is where <em>all</em>
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

        // Register every production event class plus the contract test's
        // fixture payload, so Jackson warmup produces a writer/reader for
        // the TestPayload round trip.
        List<Class<? extends DomainEvent>> allTestClasses =
                new ArrayList<>(AllEventClasses.ALL_EVENTS);
        allTestClasses.add(EventStoreContractTest.TestPayload.class);

        EventTypeRegistry registry = new EventTypeRegistry(allTestClasses);
        ObjectMapper mapper = PersistenceObjectMapper.create();
        JacksonWarmup warmup = JacksonWarmup.warmup(mapper, registry);
        EventPayloadCodec codec = new EventPayloadCodec(registry, warmup);

        store = new SqliteEventStore(dbExecutor, codec, registry, FIXED_CLOCK);
    }

    /**
     * Tears the per-test executor down so the JDBC driver releases the
     * temp-dir database file before JUnit deletes the temp directory.
     */
    @AfterEach
    void tearDown() {
        // Clear any ThreadLocal binding the store may have left on the
        // current thread — harmless on the main test thread since the store
        // is about to be discarded, but keeps the hygiene explicit.
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
                // Shutdown failures in tear-down must not mask the test's
                // primary assertion failure; swallow intentionally.
            }
            dbExecutor = null;
        }
    }
}
