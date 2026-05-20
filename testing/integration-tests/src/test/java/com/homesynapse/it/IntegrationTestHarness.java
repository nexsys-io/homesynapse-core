/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.it;

import com.homesynapse.event.AutomationCompletedEvent;
import com.homesynapse.event.AutomationTriggeredEvent;
import com.homesynapse.event.AvailabilityChangedEvent;
import com.homesynapse.event.CommandConfirmationTimedOutEvent;
import com.homesynapse.event.CommandDispatchedEvent;
import com.homesynapse.event.CommandIssuedEvent;
import com.homesynapse.event.CommandResultEvent;
import com.homesynapse.event.ConfigChangedEvent;
import com.homesynapse.event.ConfigErrorEvent;
import com.homesynapse.event.DeviceAdoptedEvent;
import com.homesynapse.event.DeviceDiscoveredEvent;
import com.homesynapse.event.DeviceRemovedEvent;
import com.homesynapse.event.DomainEvent;
import com.homesynapse.event.EventPublisher;
import com.homesynapse.event.EventStore;
import com.homesynapse.event.PresenceChangedEvent;
import com.homesynapse.event.PresenceSignalEvent;
import com.homesynapse.event.StateChangedEvent;
import com.homesynapse.event.StateConfirmedEvent;
import com.homesynapse.event.StateReportRejectedEvent;
import com.homesynapse.event.StateReportedEvent;
import com.homesynapse.event.StoragePressureChangedEvent;
import com.homesynapse.event.SystemStartedEvent;
import com.homesynapse.event.SystemStoppedEvent;
import com.homesynapse.event.TelemetrySummaryEvent;
import com.homesynapse.event.bus.BusMetrics;
import com.homesynapse.event.bus.CheckpointStore;
import com.homesynapse.event.bus.EventBus;
import com.homesynapse.event.bus.InProcessEventBusFactory;
import com.homesynapse.event.bus.test.RecordingReadConnectionFactory;
import com.homesynapse.persistence.PersistenceTestHarness;
import com.homesynapse.platform.identity.HomeId;
import com.homesynapse.platform.identity.UlidFactory;
import com.homesynapse.state.ViewCheckpointStore;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.function.IntSupplier;

/**
 * Shared test wiring that stands up the real production stack
 * (file-based SQLite + {@code InProcessEventBus}) for integration testing.
 *
 * <p>The harness exists to keep test classes focused on assertions rather
 * than composition. It is the test-time analogue of what the future M3.6
 * lifecycle composition root will do for production — but smaller, scoped
 * to exactly the surface M3.4's integration tests exercise.</p>
 *
 * <h2>Wiring sequence</h2>
 * <ol>
 *   <li>{@link PersistenceTestHarness#start} constructs and starts the
 *       SQLite persistence layer (DatabaseExecutor + migration chain
 *       V001–V004 + SqliteEventStore + SqliteCheckpointStore +
 *       SqliteViewCheckpointStore). Returns the stores typed as their
 *       public interfaces.</li>
 *   <li>{@link InProcessEventBusFactory#create} constructs the production
 *       {@code InProcessEventBus} (typed as {@link EventBus}) using the
 *       SQLite event store, the SQLite checkpoint store, the injected
 *       clock, and a per-subscriber read-executor factory.</li>
 *   <li>The harness exposes accessors for everything tests need to publish,
 *       read, and observe.</li>
 * </ol>
 *
 * <h2>Per-subscriber read executor</h2>
 *
 * <p>The harness uses {@link RecordingReadConnectionFactory} from
 * event-bus's testFixtures, which returns a synchronous read executor
 * (runs reads on the calling virtual thread). The underlying
 * {@code SqliteEventStore} still routes SQL through the persistence
 * module's platform-thread read pool, so carrier-thread JNI pinning is
 * confined as designed. This is sufficient for M3.4a's burst + heap
 * assertions; M3.6 production wiring will introduce a per-subscriber
 * dedicated platform thread.</p>
 *
 * <h2>Lifecycle</h2>
 *
 * <p>Construct via {@link #start(Path, Clock)} in a {@code @BeforeAll} or
 * {@code @BeforeEach}; call {@link #close()} in the corresponding teardown.
 * The harness is {@link AutoCloseable} for try-with-resources use.</p>
 *
 * <p>Each harness instance binds to a single SQLite database file path —
 * tests requiring isolation must use distinct {@code @TempDir} paths or
 * fresh harness instances.</p>
 */
final class IntegrationTestHarness implements AutoCloseable {

    /**
     * The full production event class list (22 core + 5 integration =
     * 27 records). Kept in this file so the harness is self-contained;
     * the canonical authoritative list lives in event-model and
     * integration-api annotation tests.
     */
    static final List<Class<? extends DomainEvent>> ALL_PRODUCTION_EVENT_CLASSES = List.of(
            CommandIssuedEvent.class,
            CommandDispatchedEvent.class,
            CommandResultEvent.class,
            CommandConfirmationTimedOutEvent.class,
            StateReportedEvent.class,
            StateReportRejectedEvent.class,
            StateChangedEvent.class,
            StateConfirmedEvent.class,
            DeviceDiscoveredEvent.class,
            DeviceAdoptedEvent.class,
            DeviceRemovedEvent.class,
            AvailabilityChangedEvent.class,
            AutomationTriggeredEvent.class,
            AutomationCompletedEvent.class,
            PresenceSignalEvent.class,
            PresenceChangedEvent.class,
            SystemStartedEvent.class,
            SystemStoppedEvent.class,
            StoragePressureChangedEvent.class,
            ConfigChangedEvent.class,
            ConfigErrorEvent.class,
            TelemetrySummaryEvent.class,
            com.homesynapse.integration.IntegrationStarted.class,
            com.homesynapse.integration.IntegrationStopped.class,
            com.homesynapse.integration.IntegrationHealthChanged.class,
            com.homesynapse.integration.IntegrationRestarted.class,
            com.homesynapse.integration.IntegrationResourceExceeded.class);

    /** Production AMD-27 default. */
    static final int DEFAULT_READ_THREAD_COUNT = 2;

    private final Clock clock;
    private final Path dbPath;
    private final HomeId homeId;
    private final PersistenceTestHarness persistence;
    private final RecordingReadConnectionFactory readConnectionFactory;
    private final EventBus eventBus;

    private IntegrationTestHarness(
            Clock clock,
            Path dbPath,
            HomeId homeId,
            PersistenceTestHarness persistence,
            RecordingReadConnectionFactory readConnectionFactory,
            EventBus eventBus) {
        this.clock = clock;
        this.dbPath = dbPath;
        this.homeId = homeId;
        this.persistence = persistence;
        this.readConnectionFactory = readConnectionFactory;
        this.eventBus = eventBus;
    }

    /**
     * Constructs and starts a fresh integration test stack against the
     * given database file path.
     *
     * <p>The path MUST point to a non-existent file in an existing
     * directory; the persistence layer creates the file on first
     * connection and runs migrations V001 through V004. Use
     * {@code @TempDir} for per-test isolation.</p>
     *
     * @param dbPath the SQLite database file path (e.g.
     *               {@code tempDir.resolve("homesynapse-events.db")});
     *               never {@code null}
     * @param clock  injected clock; never {@code null}
     * @return a started harness ready for publish / subscribe / query
     */
    static IntegrationTestHarness start(Path dbPath, Clock clock) {
        return startInternal(dbPath, clock, /* throttled */ false,
                BusMetrics.noop(), () -> 0);
    }

    /**
     * Constructs and starts a harness with the Pi-4
     * {@code ThrottledWriteCoordinator} (10 ms baseline, 200 ms spike at
     * 0.5% probability) installed inside the persistence write thread, and
     * with caller-supplied bus metrics + writer-queue-depth supplier.
     *
     * <p>This is the wiring used by M3.4b's {@code Pi4SustainedLoadIT} and
     * {@code Pi4D1SpikeIT}. Tests that need to observe the seven canonical
     * bus metrics (AMD-43 §3.6.2) — particularly {@code subscriber.lag.events}
     * for the lag-bound assertions — pass a recording {@link BusMetrics}
     * implementation here.</p>
     *
     * @param dbPath              the SQLite database file path
     * @param clock               injected clock
     * @param metrics             {@link BusMetrics} implementation; tests
     *                            that assert on metrics pass a recorder,
     *                            others pass {@link BusMetrics#noop()}
     * @param writerQueueDepth    writer-queue-depth supplier; {@code () -> 0}
     *                            is fine when the test does not assert on
     *                            rate limiting
     * @return a started harness with throttled writes and bus-metrics wired
     */
    static IntegrationTestHarness startThrottled(
            Path dbPath,
            Clock clock,
            BusMetrics metrics,
            IntSupplier writerQueueDepth) {
        Objects.requireNonNull(metrics, "metrics");
        Objects.requireNonNull(writerQueueDepth, "writerQueueDepth");
        return startInternal(dbPath, clock, /* throttled */ true,
                metrics, writerQueueDepth);
    }

    /**
     * Constructs a harness whose persistence layer will NOT shut down
     * gracefully via {@link #close()}. Used by {@code CrashRecoveryIT} to
     * simulate an ungraceful process termination.
     *
     * <p>The harness behaves identically to {@link #start(Path, Clock)}
     * until {@link #abandon()} is called: the WAL has not been flushed,
     * the {@code DatabaseExecutor} has not been shut down, and the database
     * file is in whatever state SQLite's WAL recovery sees on next open.</p>
     *
     * <p>To complete the crash simulation, a test calls {@link #abandon()}
     * then constructs a fresh {@code IntegrationTestHarness.start(...)} on
     * the same database path. The simulation works because the harness's
     * {@code @TempDir} path persists across both lifetimes (use a
     * class-level {@code static @TempDir} field or
     * {@code @TempDir(cleanup = NEVER)}).</p>
     *
     * @param dbPath the SQLite database file path
     * @param clock  injected clock
     * @return a started harness ready for the crash-simulation flow
     */
    static IntegrationTestHarness startForCrashSimulation(Path dbPath, Clock clock) {
        return startInternal(dbPath, clock, /* throttled */ false,
                BusMetrics.noop(), () -> 0);
    }

    private static IntegrationTestHarness startInternal(
            Path dbPath,
            Clock clock,
            boolean throttled,
            BusMetrics metrics,
            IntSupplier writerQueueDepth) {
        Objects.requireNonNull(dbPath, "dbPath");
        Objects.requireNonNull(clock, "clock");

        HomeId homeId = new HomeId(UlidFactory.generate(clock));

        PersistenceTestHarness persistence = throttled
                ? PersistenceTestHarness.startThrottled(
                        dbPath, DEFAULT_READ_THREAD_COUNT, clock, homeId,
                        ALL_PRODUCTION_EVENT_CLASSES)
                : PersistenceTestHarness.start(
                        dbPath, DEFAULT_READ_THREAD_COUNT, clock, homeId,
                        ALL_PRODUCTION_EVENT_CLASSES);

        RecordingReadConnectionFactory readConnectionFactory =
                new RecordingReadConnectionFactory();

        EventBus eventBus = InProcessEventBusFactory.createWithMetrics(
                persistence.eventStore(),
                persistence.checkpointStore(),
                clock,
                readConnectionFactory,
                metrics,
                writerQueueDepth);

        return new IntegrationTestHarness(
                clock, dbPath, homeId, persistence, readConnectionFactory, eventBus);
    }

    // ── Accessors ───────────────────────────────────────────────────────

    /**
     * @return the event publisher backed by the SQLite event store; this is
     *         the durable append path
     */
    EventPublisher eventPublisher() {
        return persistence.eventPublisher();
    }

    /**
     * @return the event store backed by SQLite
     */
    EventStore eventStore() {
        return persistence.eventStore();
    }

    /**
     * @return the production {@link EventBus} (an {@code InProcessEventBus})
     */
    EventBus eventBus() {
        return eventBus;
    }

    /**
     * @return the per-subscriber checkpoint store backed by SQLite
     */
    CheckpointStore checkpointStore() {
        return persistence.checkpointStore();
    }

    /**
     * @return the view (state-projection) checkpoint store backed by SQLite
     */
    ViewCheckpointStore viewCheckpointStore() {
        return persistence.viewCheckpointStore();
    }

    /**
     * @return the read-connection factory passed to the bus. Tests inspecting
     *         per-subscriber isolation (createCallCount, subscriberIds) read
     *         this directly.
     */
    RecordingReadConnectionFactory readConnectionFactory() {
        return readConnectionFactory;
    }

    /**
     * @return the harness's injected clock — the same instance the bus and
     *         the persistence layer were constructed with
     */
    Clock clock() {
        return clock;
    }

    /**
     * @return the database file path
     */
    Path dbPath() {
        return dbPath;
    }

    /**
     * @return the {@link HomeId} embedded in every event written by this
     *         harness (AMD-34)
     */
    HomeId homeId() {
        return homeId;
    }

    // ── Lifecycle ───────────────────────────────────────────────────────

    /**
     * Tears down the stack. Idempotent. The underlying
     * {@code SqlitePersistenceLifecycle.stop()} flushes WAL via
     * {@code PRAGMA wal_checkpoint(TRUNCATE)} and closes all connections.
     *
     * <p>Note: the event-bus is currently passive in this harness — there
     * are no active {@code subscribeRuntime} VTs that need draining.
     * Subscribers registered by tests should be tracked and unsubscribed
     * by those tests if they care about clean shutdown.</p>
     *
     * <p>If {@link #abandon()} was previously called, this is a no-op.</p>
     */
    @Override
    public void close() {
        persistence.close();
    }

    /**
     * Marks the underlying persistence as abandoned, simulating an
     * ungraceful process termination ({@code kill -9}). After this call,
     * the next {@link #close()} will NOT flush the WAL and will NOT shut
     * down the {@code DatabaseExecutor}. The database file is left as-is
     * on disk; SQLite's WAL recovery handles the rest on the next harness
     * open.
     *
     * <p>The {@code @TempDir} path used by the abandoned harness must be
     * shared with the restart-phase harness — use a class-level
     * {@code static @TempDir Path tempDir} field or
     * {@code @TempDir(cleanup = NEVER)} to keep the file across instances.</p>
     */
    void abandon() {
        persistence.abandonForCrashSimulation();
    }
}
