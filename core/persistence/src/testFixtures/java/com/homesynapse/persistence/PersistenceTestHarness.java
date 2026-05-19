/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import com.homesynapse.event.DomainEvent;
import com.homesynapse.event.EventPublisher;
import com.homesynapse.event.EventStore;
import com.homesynapse.event.bus.CheckpointStore;
import com.homesynapse.platform.identity.HomeId;
import com.homesynapse.state.ViewCheckpointStore;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Objects;

/**
 * Public test fixture that wraps the package-private
 * {@link SqlitePersistenceLifecycle} and exposes its constructed stores via
 * the public interface types they implement.
 *
 * <p>Direct construction of {@link SqlitePersistenceLifecycle},
 * {@link SqliteEventStore}, {@link SqliteCheckpointStore}, and
 * {@link SqliteViewCheckpointStore} is not possible from outside the
 * {@code com.homesynapse.persistence} package because all four types are
 * package-private. This harness lives in the persistence module's
 * {@code testFixtures} source set (same package, full access) and exposes
 * only public-interface return types, so integration tests in other modules
 * (e.g. {@code testing:integration-tests}) can wire up the real production
 * persistence stack without depending on package-private symbols.</p>
 *
 * <p>This fixture is NOT a replacement for the future composition-root
 * lifecycle module (M3.6). It exists solely to make on-device integration
 * testing possible before the composition root lands. Production code MUST
 * NOT depend on this class.</p>
 *
 * <h2>Lifecycle</h2>
 *
 * <p>{@link #start(Path, int, Clock, HomeId, List)} constructs the underlying
 * {@code SqlitePersistenceLifecycle}, calls its {@code start()} synchronously
 * (the future is already completed when {@code start()} returns), and returns
 * a ready-to-use harness instance.</p>
 *
 * <p>{@link #close()} delegates to the lifecycle's {@code stop()} method,
 * flushing the WAL via {@code PRAGMA wal_checkpoint(TRUNCATE)} and closing
 * all database connections. Implementing {@link AutoCloseable} makes the
 * harness usable in try-with-resources blocks.</p>
 *
 * @see SqlitePersistenceLifecycle
 */
public final class PersistenceTestHarness implements AutoCloseable {

    private final SqlitePersistenceLifecycle lifecycle;

    private PersistenceTestHarness(SqlitePersistenceLifecycle lifecycle) {
        this.lifecycle = lifecycle;
    }

    /**
     * Starts a new persistence stack against the given database file path.
     *
     * <p>Runs all migrations (V001–V004) on a fresh database. Subsequent
     * accessor calls return the constructed stores.</p>
     *
     * @param databasePath    full path to the SQLite database file (typically
     *                        a {@code @TempDir} path); never {@code null}
     * @param readThreadCount number of read connections/threads (default 2);
     *                        must be &gt;= 1
     * @param clock           injected clock; never {@code null}
     * @param homeId          home identity for this installation (AMD-34);
     *                        never {@code null}
     * @param eventClasses    domain-event record classes to register for
     *                        polymorphic serialization; never {@code null} or
     *                        empty
     * @return a started harness with all stores constructed and ready
     */
    public static PersistenceTestHarness start(
            Path databasePath,
            int readThreadCount,
            Clock clock,
            HomeId homeId,
            List<Class<? extends DomainEvent>> eventClasses) {
        Objects.requireNonNull(databasePath, "databasePath");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(homeId, "homeId");
        Objects.requireNonNull(eventClasses, "eventClasses");

        SqlitePersistenceLifecycle lifecycle = new SqlitePersistenceLifecycle(
                databasePath, readThreadCount, clock, homeId, eventClasses);
        lifecycle.start().join();
        return new PersistenceTestHarness(lifecycle);
    }

    /**
     * Returns the event publisher (durable append path through the SQLite
     * event store).
     *
     * @return the production {@code EventPublisher} backed by SQLite
     */
    public EventPublisher eventPublisher() {
        return lifecycle.eventStore();
    }

    /**
     * Returns the event store (read interface over the SQLite event log).
     *
     * @return the production {@code EventStore} backed by SQLite
     */
    public EventStore eventStore() {
        return lifecycle.eventStore();
    }

    /**
     * Returns the subscriber checkpoint store backed by SQLite.
     *
     * @return the production {@code CheckpointStore}
     */
    public CheckpointStore checkpointStore() {
        return lifecycle.checkpointStore();
    }

    /**
     * Returns the view checkpoint store backed by SQLite.
     *
     * @return the production {@code ViewCheckpointStore}
     */
    public ViewCheckpointStore viewCheckpointStore() {
        return lifecycle.viewCheckpointStore();
    }

    /**
     * Stops the underlying persistence lifecycle: flushes WAL via
     * {@code PRAGMA wal_checkpoint(TRUNCATE)} and closes all connections.
     */
    @Override
    public void close() {
        lifecycle.stop();
    }
}
