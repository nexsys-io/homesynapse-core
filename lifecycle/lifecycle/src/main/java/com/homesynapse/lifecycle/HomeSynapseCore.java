/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.lifecycle;

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

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Composition root for the HomeSynapse Core runtime (M3.6d-b).
 *
 * <p>{@code HomeSynapseCore} is the single owner of every long-lived
 * subsystem: persistence, event bus, state projection, scheduler, rate
 * limit, and health check. It constructs these in a fixed twelve-step
 * sequence on {@link #start()} and tears them down in reverse order on
 * {@link #stop()}.</p>
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
 *   <li>Set {@code started = true}.</li>
 *   <li>Return a completed {@link CompletableFuture}.</li>
 * </ol>
 *
 * <p>Shutdown ({@link #stop()}) reverses that order: stop the scheduler,
 * unsubscribe the projection (which closes its dedicated read connection),
 * then close the persistence layer (flushing WAL).</p>
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

        // Step 11 — Mark started.
        this.started = true;
        LOG.info("HomeSynapseCore started: db={}, homeId={}", dbPath, homeId.value());

        // Step 12 — Return a completed future.
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Tears down the runtime in reverse order: scheduler first (stops the
     * periodic tasks so they cannot touch resources being torn down), then
     * unsubscribe the projection (closes its dedicated read connection),
     * then close persistence (flushes WAL, closes all connections).
     *
     * <p>Idempotent — repeated calls after the first are no-ops.</p>
     */
    public void stop() {
        if (!started) {
            return;
        }
        started = false;

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
     * Returns a {@link StateQueryService} placeholder. M3.6d-b returns a
     * {@link ThrowingStateQueryService} that fails every call with
     * {@link IllegalStateException} until M3.6e lands the real
     * {@code MaterializedStateQueryService}.
     *
     * @return the placeholder query service
     * @throws IllegalStateException if {@link #start()} has not been called
     */
    public StateQueryService stateQueryService() {
        requireStarted();
        return new ThrowingStateQueryService();
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
