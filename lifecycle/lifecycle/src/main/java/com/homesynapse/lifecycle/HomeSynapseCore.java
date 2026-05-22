/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.lifecycle;

import com.homesynapse.api.rest.RestFilters;
import com.homesynapse.event.DomainEvent;
import com.homesynapse.event.EventPublisher;
import com.homesynapse.event.EventStore;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.bus.BusMetrics;
import com.homesynapse.event.bus.DerivedWriteRateLimit;
import com.homesynapse.event.bus.EventBus;
import com.homesynapse.event.bus.HealthSignal;
import com.homesynapse.event.bus.InProcessEventBus;
import com.homesynapse.event.bus.QueueSaturationHealthCheck;
import com.homesynapse.event.bus.SubscriberInfo;
import com.homesynapse.event.bus.SubscriberMode;
import com.homesynapse.event.bus.SubscriptionFilter;
import com.homesynapse.integration.IntegrationEvents;
import com.homesynapse.persistence.DeploymentProfile;
import com.homesynapse.persistence.PersistenceFactory;
import com.homesynapse.platform.identity.HomeId;
import com.homesynapse.state.AdvanceResult;
import com.homesynapse.state.DerivationRule;
import com.homesynapse.state.DerivedPublishGate;
import com.homesynapse.state.FixedCheckpointPolicy;
import com.homesynapse.state.ProjectionAdvancer;
import com.homesynapse.state.ProjectionId;
import com.homesynapse.state.ReadinessSource;
import com.homesynapse.state.StateProjection;
import com.homesynapse.state.StateQueryService;

import io.javalin.Javalin;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Composition root for the HomeSynapse Core runtime (M3.6d-b).
 *
 * <p>{@code HomeSynapseCore} is the single owner of every long-lived
 * subsystem: persistence, event bus, state projection, scheduler, rate
 * limit, health check, query service, and the embedded HTTP server. It
 * constructs these in a fixed sequence on {@link #start()} and tears them
 * down in reverse order on {@link #stop()}.</p>
 *
 * <h2>Bootstrap sequence</h2>
 * <ol>
 *   <li>{@link PersistenceFactory#start} — opens the database, registers
 *       event types, brings up stores, runs migrations.</li>
 *   <li>{@link BusMetrics#jfr()} — JFR-native bus metrics emitter.</li>
 *   <li>{@link InProcessEventBus} — wired to the persistence stores and the
 *       writer-queue-depth supplier.</li>
 *   <li>State store + checkpoint source.</li>
 *   <li>{@link DerivedWriteRateLimit} — bounds the projection's derived
 *       publishes (AMD-43 §3.6.4).</li>
 *   <li>{@link StateProjection} — materialized view subscriber.</li>
 *   <li>Bus subscription via
 *       {@link EventBus#subscribeRuntime(SubscriberInfo, com.homesynapse.event.bus.Subscriber)}.</li>
 *   <li>Health-signal handler — SLF4J bridge (real observability bridge is
 *       a future WU).</li>
 *   <li>{@link QueueSaturationHealthCheck} — hysteresis state machine over
 *       the writer-queue depth.</li>
 *   <li>{@link SharedScheduler} — drives the rate-limit refill cadence
 *       (50 ms) and the saturation tick cadence (1 s).</li>
 *   <li>{@code MaterializedStateQueryService} (M3.6e.1) — production
 *       {@link StateQueryService} backed by the projection's
 *       {@code StateStore} and this instance as {@link ReadinessSource}.</li>
 *   <li>{@link Javalin} HTTP server (M3.6e.1) — embedded Jetty pool sized
 *       by {@link DeploymentProfile#javalinMinThreads} /
 *       {@link DeploymentProfile#javalinMaxThreads}, with the readiness
 *       gate installed via
 *       {@link RestFilters#installReadinessGate(Object, com.homesynapse.state.ReadinessSource)}
 *       and bound on port {@value #HTTP_PORT}.</li>
 *   <li>Entity query endpoints (M3.6e.2) — {@code GET /api/v1/entities},
 *       {@code GET /api/v1/entities/{entityId}}, and
 *       {@code GET /api/v1/entities/{entityId}/state} registered via
 *       {@link RestFilters#installEntityQueryEndpoints(Object,
 *       StateQueryService, java.util.function.LongSupplier, Clock)}.
 *       All three are gated by the readiness filter.</li>
 *   <li>Admin endpoints (M3.6e.2) — {@code GET /internal/dlq} and
 *       {@code GET /internal/projection} registered via
 *       {@link RestFilters#installAdminEndpoints(Object, Object,
 *       com.homesynapse.state.ReadinessSource, StateQueryService,
 *       java.util.function.LongSupplier)}. Intentionally outside the
 *       readiness gate per SD-5 — operators need them during REPLAY.</li>
 *   <li>Set {@code started = true}.</li>
 *   <li>Return a completed {@link CompletableFuture}.</li>
 * </ol>
 *
 * <p>Shutdown ({@link #stop()}) reverses that order: stop the HTTP server
 * first (refuse new queries before tearing down the state they would
 * query), then stop the scheduler, unsubscribe the projection (which closes
 * its dedicated read connection), then close persistence (flushing WAL).</p>
 *
 * <h2>Threading</h2>
 *
 * <p>{@link #start()} MUST be invoked from a platform thread — Jackson
 * warmup inside {@link PersistenceFactory#start} parks on
 * {@code Class.forName} cache miss paths that pin virtual thread carriers
 * (LTD-19 / DECIDE-M2-05). The production entry point is {@code main()},
 * which satisfies this.</p>
 *
 * <h2>Readiness</h2>
 *
 * <p>Implements {@link ReadinessSource}. Before {@link #start()},
 * {@link #mode()} returns {@link SubscriberMode#COLD}; once started, the
 * call delegates to {@link StateProjection#currentMode()}.</p>
 *
 * @see PersistenceFactory
 * @see InProcessEventBus
 * @see StateProjection
 * @see SharedScheduler
 */
public final class HomeSynapseCore implements ReadinessSource {

    private static final Logger LOG = LoggerFactory.getLogger(HomeSynapseCore.class);

    /** Subscriber identifier used for the materialized state projection. */
    private static final String PROJECTION_SUBSCRIBER_ID = "state_projection";

    /**
     * Default HTTP port for the embedded Javalin server (M3.6e.1). Hardcoded
     * per PLAN-M3 §10 for the MVP; future work makes this configurable
     * through {@link HomeSynapseConfig}.
     */
    private static final int HTTP_PORT = 7070;

    /**
     * Default derivation rule for M3.6d-b composition wiring (OR-M3-15).
     *
     * <p>No production {@link DerivationRule} implementation exists in the
     * state-store module's main source set yet. A no-op rule is correct for
     * M3.6d-b because the projection's {@code applyToState} already handles
     * the core materialization path (state_reported → state map update);
     * {@code DerivationRule} is the hook for emitting additional
     * {@code state_changed} events whose primary consumer (the automation
     * engine) is M5 scope. To be replaced when the production derivation
     * rule lands.</p>
     */
    private static final DerivationRule NO_OP_DERIVATION =
            context -> List.of(); // OR-M3-15

    /**
     * Default projection advancer for M3.6d-b composition wiring (OR-M3-16).
     *
     * <p>No production {@link ProjectionAdvancer} implementation exists in
     * the state-store module's main source set yet (only the test fixture
     * {@code InMemoryProjectionAdvancer}). The advancer is only invoked by
     * {@link StateProjection#processBatch(int)}, which is not called in the
     * production LIVE delivery path — the bus's per-subscriber VT delivers
     * envelopes through {@link StateProjection#onEvent} directly. Returning
     * a {@link AdvanceResult} with no events processed and
     * {@code hasMore=false} is correct for the no-batch wiring.</p>
     *
     * <p>MUST be resolved before M3.7 — end-to-end REPLAY tests exercise the
     * advancer.</p>
     */
    private static final ProjectionAdvancer NO_OP_ADVANCER =
            (fromPosition, maxRows, processor) ->
                    new AdvanceResult(fromPosition, 0, false); // OR-M3-16

    private final Path dbPath;
    private final HomeSynapseConfig config;
    private final Clock clock;
    private final HomeId homeId;

    // Constructed during start()
    private PersistenceFactory persistenceFactory;
    private InProcessEventBus eventBus;
    private StateProjection stateProjection;
    private SharedScheduler scheduler;
    private DerivedWriteRateLimit rateLimit;
    private QueueSaturationHealthCheck healthCheck;
    private StateQueryService stateQueryService;
    private Javalin httpServer;
    private volatile boolean started = false;

    /**
     * Constructs a new composition root.
     *
     * @param dbPath full path to the SQLite database file; never {@code null}
     * @param config consolidated runtime configuration; never {@code null}.
     *               Use {@link HomeSynapseConfig#HOME_DEFAULT} for the MVP
     *               default.
     * @param clock  injected clock; never {@code null}
     * @param homeId home identity for this installation (AMD-34); never
     *               {@code null}
     */
    public HomeSynapseCore(Path dbPath,
                           HomeSynapseConfig config,
                           Clock clock,
                           HomeId homeId) {
        this.dbPath = Objects.requireNonNull(dbPath, "dbPath");
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.homeId = Objects.requireNonNull(homeId, "homeId");
    }

    /**
     * Brings up the runtime. Constructs every subsystem in dependency order,
     * subscribes the state projection, and starts the shared scheduler.
     *
     * <p>Returns an already-completed {@link CompletableFuture} so callers
     * may chain shutdown logic uniformly with future async-start variants.
     * Failures throw on the calling thread.</p>
     *
     * @return a completed future when start succeeds
     * @throws IllegalStateException if already started
     * @throws RuntimeException      if any subsystem fails to initialize
     */
    public CompletableFuture<Void> start() {
        if (started) {
            throw new IllegalStateException("HomeSynapseCore already started");
        }

        // Step 1 — Persistence subsystem.
        List<Class<? extends DomainEvent>> eventClasses = Stream.concat(
                EventTypes.CORE_PRODUCTION_EVENT_CLASSES.stream(),
                IntegrationEvents.LIFECYCLE_EVENT_CLASSES.stream()
        ).toList();
        this.persistenceFactory = PersistenceFactory.start(
                dbPath, config.persistence(), clock, homeId, eventClasses);

        // Step 2 — Bus metrics.
        BusMetrics jfrMetrics = BusMetrics.jfr();

        // Step 3 — Event bus.
        this.eventBus = new InProcessEventBus(
                persistenceFactory.eventStore(),
                persistenceFactory.checkpointStore(),
                clock,
                persistenceFactory.subscriberReadConnectionFactory(),
                jfrMetrics,
                persistenceFactory.writeQueueDepthSupplier(),
                config.eventBus());

        // Step 4 — State store + checkpoint source (same instance, two roles).
        // Both flow through the persistence factory's public-interface accessors.

        // Step 5 — Derived write rate limit.
        this.rateLimit = new DerivedWriteRateLimit(
                clock, jfrMetrics, PROJECTION_SUBSCRIBER_ID);

        // Step 6 — State projection.
        DerivedPublishGate publishGate = rateLimit::acquire;
        this.stateProjection = StateProjection.create(
                new ProjectionId(PROJECTION_SUBSCRIBER_ID),
                1,
                persistenceFactory.viewCheckpointStore(),
                persistenceFactory.stateCheckpointSource(),
                persistenceFactory.stateStore(),
                NO_OP_DERIVATION,                          // OR-M3-15
                persistenceFactory.eventPublisher(),
                NO_OP_ADVANCER,                            // OR-M3-16
                FixedCheckpointPolicy.HOME_DEFAULT,        // AMD-38
                clock,
                publishGate);

        // Step 7 — Subscribe the projection (coalesceExempt — Doc 01 §3.6).
        SubscriberInfo projectionInfo = new SubscriberInfo(
                PROJECTION_SUBSCRIBER_ID,
                SubscriptionFilter.all(),
                true);
        eventBus.subscribeRuntime(projectionInfo, stateProjection);

        // Step 8 — Health signal handler. SLF4J bridge for now; the real
        // observability bridge (HealthAggregator wiring) lands in a future WU.
        Consumer<HealthSignal> healthSignalHandler = signal -> {
            switch (signal.level()) {
                case INFO -> LOG.info("Health {}: depth={} at {}",
                        signal.channel(), signal.depth(), signal.timestamp());
                case WARN -> LOG.warn("Health {}: depth={} at {}",
                        signal.channel(), signal.depth(), signal.timestamp());
                case CRITICAL -> LOG.error("Health CRITICAL {}: depth={} at {}",
                        signal.channel(), signal.depth(), signal.timestamp());
            }
        };

        // Step 9 — Queue saturation health check (AMD-43 §3.6.3).
        this.healthCheck = new QueueSaturationHealthCheck(
                persistenceFactory.writeQueueDepthSupplier(),
                clock,
                5_000,
                10_000,
                5,
                healthSignalHandler);

        // Step 10 — Shared scheduler (50ms refill + 1s tick cadence).
        this.scheduler = new SharedScheduler(rateLimit, healthCheck);

        // Step 11 — Materialized state query service (M3.6e.1, DEC-M3-16).
        // Wired via the StateStore + this ReadinessSource + the projection's
        // cursor for view position + the injected clock for staleness
        // recomputation at read time (Doc 03 §3.8, AMD-11). The
        // implementation lives package-private in com.homesynapse.state and
        // is reached via the static factory on StateQueryService.
        this.stateQueryService = StateQueryService.materialized(
                persistenceFactory.stateStore(),
                this,
                stateProjection::cursorPosition,
                clock);

        // Step 12 — Embedded Javalin HTTP server (M3.6e.1). Jetty pool sized
        // by the deployment profile so Pi-class hardware doesn't spend half
        // its carrier budget on HTTP. ReadinessFilter gates /api/* until the
        // projection reaches LIVE. Banner suppressed (we are a headless
        // embedded system, not a web app).
        DeploymentProfile profile = config.persistence().profile();
        QueuedThreadPool threadPool = new QueuedThreadPool(
                profile.javalinMaxThreads(),
                profile.javalinMinThreads());
        threadPool.setName("hs-http");
        Javalin app = Javalin.create(cfg -> {
            cfg.jetty.threadPool = threadPool;       // @JvmField var on JettyConfig
            cfg.showJavalinBanner = false;           // @JvmField var on JavalinConfig
        });
        RestFilters.installReadinessGate(app, this);

        // Step 13 — Entity query endpoints (M3.6e.2). All three live under
        // /api/* and are therefore gated by the readiness filter installed
        // at step 12. View position is the projection's cursor; the clock
        // supplies response timestamps (DEC-M3-09).
        RestFilters.installEntityQueryEndpoints(
                app,
                stateQueryService,
                stateProjection::cursorPosition,
                clock);

        // Step 14 — Admin/operational endpoints (M3.6e.2). /internal/* is
        // intentionally outside the readiness filter — operators need DLQ
        // and projection visibility during REPLAY/COLD/TRANSITION (SD-5).
        RestFilters.installAdminEndpoints(
                app,
                eventBus,
                this,
                stateQueryService,
                stateProjection::cursorPosition);

        app.start(HTTP_PORT);
        this.httpServer = app;

        // Step 15 — Mark started.
        this.started = true;
        LOG.info("HomeSynapseCore started: db={}, homeId={}, http=:{}",
                dbPath, homeId.value(), HTTP_PORT);

        // Step 16 — Return a completed future.
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Tears down the runtime in reverse order: HTTP server first (refuse new
     * queries before tearing down the state they would query), then
     * scheduler (stops the periodic tasks so they cannot touch resources
     * being torn down), then unsubscribe the projection (closes its
     * dedicated read connection), then close persistence (flushes WAL,
     * closes all connections).
     *
     * <p>Idempotent — repeated calls after the first are no-ops.</p>
     */
    public void stop() {
        if (!started) {
            return;
        }
        started = false;

        if (httpServer != null) {
            httpServer.stop();
        }
        if (scheduler != null) {
            scheduler.shutdown();
        }
        if (eventBus != null) {
            eventBus.unsubscribe(PROJECTION_SUBSCRIBER_ID);
        }
        if (rateLimit != null) {
            rateLimit.close();
        }
        if (persistenceFactory != null) {
            persistenceFactory.close();
        }
        LOG.info("HomeSynapseCore stopped: db={}", dbPath);
    }

    /**
     * Returns the event publisher.
     *
     * @return the production {@link EventPublisher}
     * @throws IllegalStateException if {@link #start()} has not been called
     */
    public EventPublisher eventPublisher() {
        requireStarted();
        return persistenceFactory.eventPublisher();
    }

    /**
     * Returns the event store.
     *
     * @return the production {@link EventStore}
     * @throws IllegalStateException if {@link #start()} has not been called
     */
    public EventStore eventStore() {
        requireStarted();
        return persistenceFactory.eventStore();
    }

    /**
     * Returns the in-process event bus.
     *
     * @return the production {@link EventBus}
     * @throws IllegalStateException if {@link #start()} has not been called
     */
    public EventBus eventBus() {
        requireStarted();
        return eventBus;
    }

    /**
     * Returns the production {@link StateQueryService} (M3.6e.1) — a
     * {@code MaterializedStateQueryService} backed by the State Projection's
     * live {@code StateStore} with read-time staleness recomputation. Reads
     * are lock-free; consumers may call this from any thread including
     * virtual threads. Returns the same instance on every call after
     * {@link #start()}.
     *
     * @return the materialized query service
     * @throws IllegalStateException if {@link #start()} has not been called
     */
    public StateQueryService stateQueryService() {
        requireStarted();
        return stateQueryService;
    }

    @Override
    public SubscriberMode mode() {
        if (!started) {
            return SubscriberMode.COLD;
        }
        return stateProjection.currentMode();
    }

    private void requireStarted() {
        if (!started) {
            throw new IllegalStateException("HomeSynapseCore not started");
        }
    }
}
