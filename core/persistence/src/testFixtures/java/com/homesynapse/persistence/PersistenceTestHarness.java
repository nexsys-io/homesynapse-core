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
import java.util.function.Function;

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
 * <p>M3.4b adds {@link #startWithWriteCoordinator} (test-fixture decorator
 * injection) and {@link #abandonForCrashSimulation} (ungraceful-shutdown
 * simulation).</p>
 *
 * @see SqlitePersistenceLifecycle
 */
public final class PersistenceTestHarness implements AutoCloseable {

    private final SqlitePersistenceLifecycle lifecycle;
    private volatile boolean abandoned;

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
        return startWithWriteCoordinator(
                databasePath, readThreadCount, clock, homeId, eventClasses,
                Function.identity());
    }

    /**
     * Starts a new persistence stack with a {@link java.util.function.Function}
     * decorator applied to the underlying {@code WriteCoordinator}.
     *
     * <p>{@code WriteCoordinator} is package-private to the persistence module
     * and intentionally not exposed to consumers. The decorator function
     * receives the production {@code PlatformThreadWriteCoordinator} and
     * returns a replacement (typically a wrapper) implementing the same
     * interface. The most common use is M3.4b's
     * {@code ThrottledWriteCoordinator::withDefaults}, which inserts Pi-4-
     * equivalent baseline + spike latencies on the write thread.</p>
     *
     * <p>Pass {@link Function#identity()} to get the same behavior as
     * {@link #start(Path, int, Clock, HomeId, List)}.</p>
     *
     * @param databasePath              full path to the SQLite database file
     * @param readThreadCount           number of read connections/threads
     * @param clock                     injected clock
     * @param homeId                    home identity for this installation
     * @param eventClasses              domain-event record classes
     * @param coordinatorDecorator      decorator applied to the
     *                                  {@code WriteCoordinator}; never
     *                                  {@code null}
     * @return a started harness with the decorated coordinator installed
     */
    public static PersistenceTestHarness startWithWriteCoordinator(
            Path databasePath,
            int readThreadCount,
            Clock clock,
            HomeId homeId,
            List<Class<? extends DomainEvent>> eventClasses,
            Function<WriteCoordinator, WriteCoordinator> coordinatorDecorator) {
        Objects.requireNonNull(databasePath, "databasePath");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(homeId, "homeId");
        Objects.requireNonNull(eventClasses, "eventClasses");
        Objects.requireNonNull(coordinatorDecorator, "coordinatorDecorator");

        SqlitePersistenceLifecycle lifecycle = new SqlitePersistenceLifecycle(
                databasePath, readThreadCount, clock, homeId, eventClasses,
                coordinatorDecorator);
        lifecycle.start().join();
        return new PersistenceTestHarness(lifecycle);
    }

    /**
     * Starts a persistence stack with the M3.4b
     * {@code ThrottledWriteCoordinator} installed using its default Pi-4
     * profile (10 ms baseline, 200 ms spike at 0.5% probability).
     *
     * <p>Cross-module convenience: {@code ThrottledWriteCoordinator} is
     * package-private to {@code com.homesynapse.persistence}, so consumers
     * outside the persistence package (e.g. {@code IntegrationTestHarness}
     * in the {@code testing/integration-tests} module) cannot construct one
     * directly. This factory encapsulates the decorator wiring and exposes
     * the throttled behavior under a stable named entry point.</p>
     *
     * @param databasePath    full path to the SQLite database file
     * @param readThreadCount number of read connections/threads
     * @param clock           injected clock
     * @param homeId          home identity for this installation
     * @param eventClasses    domain-event record classes
     * @return a started harness whose writes incur Pi-4-equivalent latency
     */
    public static PersistenceTestHarness startThrottled(
            Path databasePath,
            int readThreadCount,
            Clock clock,
            HomeId homeId,
            List<Class<? extends DomainEvent>> eventClasses) {
        return startWithWriteCoordinator(
                databasePath, readThreadCount, clock, homeId, eventClasses,
                ThrottledWriteCoordinator::withDefaults);
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
     * Marks the harness as abandoned for ungraceful-shutdown simulation
     * (M3.4b {@code CrashRecoveryIT}).
     *
     * <p>After this call, subsequent {@link #close()} calls are no-ops:
     * {@code SqlitePersistenceLifecycle.stop()} is NEVER invoked. The
     * consequence is that the WAL is NOT flushed via
     * {@code PRAGMA wal_checkpoint(TRUNCATE)} and the {@code DatabaseExecutor}
     * is NOT shut down — the JVM holds onto file descriptors, the write
     * thread keeps running, and the SQLite database file remains as-is on
     * disk with any uncheckpointed pages still in the {@code -wal} sidecar.</p>
     *
     * <p>This simulates a {@code kill -9} of the production process. SQLite's
     * automatic WAL recovery handles the rest when a fresh harness opens the
     * same database file on the next run.</p>
     *
     * <p><strong>Test-only.</strong> Production code MUST call {@link #close()}
     * (which invokes the lifecycle's graceful shutdown). Calling this method
     * after {@link #close()} is a no-op. Calling {@link #close()} after this
     * method is a no-op. The two methods are mutually exclusive — at most one
     * has any effect per harness instance.</p>
     */
    public void abandonForCrashSimulation() {
        abandoned = true;
    }

    /**
     * Stops the underlying persistence lifecycle: flushes WAL via
     * {@code PRAGMA wal_checkpoint(TRUNCATE)} and closes all connections.
     *
     * <p>If {@link #abandonForCrashSimulation()} was previously invoked, this
     * method is a no-op.</p>
     */
    @Override
    public void close() {
        if (abandoned) {
            return;
        }
        lifecycle.stop();
    }
}
