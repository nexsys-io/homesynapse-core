/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.runtime;

import com.homesynapse.config.ConfigurationAccess;
import com.homesynapse.device.EntityRegistry;
import com.homesynapse.event.DomainEvent;
import com.homesynapse.event.EventDraft;
import com.homesynapse.event.EventOrigin;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventPublisher;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.SequenceConflictException;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.integration.BackoffParameters;
import com.homesynapse.integration.HealthState;
import com.homesynapse.integration.IntegrationAdapter;
import com.homesynapse.integration.IntegrationContext;
import com.homesynapse.integration.IntegrationDescriptor;
import com.homesynapse.integration.IntegrationFactory;
import com.homesynapse.integration.IntegrationHealthChanged;
import com.homesynapse.integration.IntegrationRestarted;
import com.homesynapse.integration.IntegrationStarted;
import com.homesynapse.integration.IntegrationStopped;
import com.homesynapse.integration.IoType;
import com.homesynapse.integration.IsolationLevel;
import com.homesynapse.integration.PermanentIntegrationException;
import com.homesynapse.platform.identity.IntegrationId;
import com.homesynapse.state.StateQueryService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * The first {@link IntegrationSupervisor} implementation — the M9.1
 * minimal-but-real slice: descriptor-driven registration and thread
 * allocation (DP-11), the HEALTHY ↔ restart-cycle → FAILED health FSM
 * (DP-10), lifecycle-event publication over the EXISTING event types (DP-9,
 * zero mint), and grace-bounded shutdown (Doc 05 §3.6).
 *
 * <h2>The M9.1 slice boundary (deferred breadth)</h2>
 *
 * <p>DEGRADED/SUSPENDED, the probe ladder, suspension cycles, JFR resource
 * quotas, planned-restart suppression behaviors (Doc 05 §3.14 — only the
 * {@code plannedRestart} flag is carried), dependency-graph (Kahn) startup
 * ordering, and integration-scoped registry/query wrappers are OUT — the
 * post-hero supervisor-breadth unit owns them (NQ-6 validates restart
 * defaults first). Startup order is factory-list order; shutdown is its
 * reverse.</p>
 *
 * <h2>Contracts held</h2>
 *
 * <ul>
 *   <li><strong>INV-RF-01:</strong> every throwable escaping the adapter
 *       boundary is caught and classified ({@link ExceptionClassifier},
 *       Doc 05 §3.7); a failing integration never takes down core or its
 *       siblings.</li>
 *   <li><strong>INV-RF-03 (CONTRACT NOTE):</strong> {@code initialize()} must
 *       not block on external device connectivity. M9.1 does not enforce a
 *       timeout around it — the composition root bounds the whole
 *       {@code start(...)} instead (DP-8) and the real enforcement arrives
 *       with the serial adapter (M9.2).</li>
 *   <li><strong>W8:</strong> restart backoff is clock-driven — the run loop
 *       re-checks {@code clock.instant()} against its deadline on short
 *       interrupt-safe wait quanta, so a stepped test clock advances the
 *       schedule deterministically (never a wall-clock
 *       {@code Thread.sleep(backoffMillis)}).</li>
 *   <li><strong>LTD-11:</strong> all mutation under a {@link ReentrantLock};
 *       no {@code synchronized}. Publishes and adapter calls happen outside
 *       the lock.</li>
 * </ul>
 *
 * <p>Thread-safe. All time via the injected {@link Clock}
 * (NO_DIRECT_TIME_ACCESS).</p>
 */
final class StandardIntegrationSupervisor implements IntegrationSupervisor {

    private static final Logger LOG =
            LoggerFactory.getLogger(StandardIntegrationSupervisor.class);

    /** Doc 05 §3.6 default per-adapter close grace. */
    static final Duration DEFAULT_CLOSE_GRACE = Duration.ofSeconds(10);

    /**
     * The interrupt-safe wait quantum for clock-driven backoff deadlines (W8).
     * Real time bounds only how quickly a clock step is OBSERVED — the deadline
     * itself is pure injected-clock arithmetic. The short real-time poll is the
     * same primitive the composition root's LIVE-await uses.
     */
    private static final long WAIT_QUANTUM_MILLIS = 10L;

    private static final int SCHEMA_VERSION = 1;

    private final EventPublisher publisher;
    private final EntityRegistry entityRegistry;
    private final StateQueryService stateQueryService;
    private final Function<String, ConfigurationAccess> configAccessFactory;
    private final Clock clock;
    private final Duration closeGrace;

    private final ReentrantLock stateLock = new ReentrantLock();
    /** Registration-ordered — shutdown iterates the reverse. Guarded by {@link #stateLock}. */
    private final Map<IntegrationId, IntegrationRuntime> runtimes = new LinkedHashMap<>();
    private boolean startInvoked;
    private volatile boolean supervisorStopping;

    StandardIntegrationSupervisor(EventPublisher publisher,
                                  EntityRegistry entityRegistry,
                                  StateQueryService stateQueryService,
                                  Function<String, ConfigurationAccess> configAccessFactory,
                                  Clock clock,
                                  Duration closeGrace) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.entityRegistry = Objects.requireNonNull(entityRegistry, "entityRegistry");
        this.stateQueryService = Objects.requireNonNull(stateQueryService, "stateQueryService");
        this.configAccessFactory = Objects.requireNonNull(configAccessFactory, "configAccessFactory");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.closeGrace = Objects.requireNonNull(closeGrace, "closeGrace");
    }

    // ════════════════════════════════════════════════════════════════════════
    // IntegrationSupervisor
    // ════════════════════════════════════════════════════════════════════════

    @Override
    public CompletableFuture<Void> start(List<IntegrationFactory> factories) {
        Objects.requireNonNull(factories, "factories");
        if (factories.isEmpty()) {
            throw new IllegalArgumentException("factories must not be empty");
        }
        // Descriptor pass — pure, no I/O (the IntegrationFactory.descriptor()
        // contract). The descriptor record's compact constructor already
        // guarantees a non-blank integrationType; duplicates and reserved
        // isolation levels are validated here, before any adapter exists.
        List<IntegrationDescriptor> descriptors = new ArrayList<>();
        Set<String> seenTypes = new HashSet<>();
        for (IntegrationFactory factory : factories) {
            IntegrationDescriptor descriptor =
                    Objects.requireNonNull(factory.descriptor(), "descriptor");
            if (!seenTypes.add(descriptor.integrationType())) {
                throw new IllegalArgumentException(
                        "Duplicate integrationType '" + descriptor.integrationType()
                                + "' in the factory list");
            }
            if (descriptor.isolationLevel() == IsolationLevel.RESERVED_SUBPROCESS) {
                throw new UnsupportedOperationException(
                        "IsolationLevel RESERVED_SUBPROCESS is not supported (AMD-63-INV-01): "
                                + descriptor.integrationType());
            }
            descriptors.add(descriptor);
        }

        List<IntegrationRuntime> registered = new ArrayList<>();
        stateLock.lock();
        try {
            if (startInvoked) {
                throw new IllegalStateException(
                        "IntegrationSupervisor.start(...) already invoked");
            }
            startInvoked = true;
            Instant now = clock.instant();
            for (int i = 0; i < factories.size(); i++) {
                IntegrationDescriptor descriptor = descriptors.get(i);
                IntegrationId id = IntegrationIds.deriveStable(descriptor.integrationType());
                IntegrationRuntime runtime =
                        new IntegrationRuntime(id, descriptor, factories.get(i), now);
                runtimes.put(id, runtime);
                registered.add(runtime);
            }
        } finally {
            stateLock.unlock();
        }

        // The future completes when every factory has been LAUNCHED-or-FAILED —
        // never on adapter health (INV-RF-03: a failing integration never blocks
        // startup; markFailed records it and the loop continues with siblings).
        CompletableFuture<Void> startFuture = new CompletableFuture<>();
        Thread.ofVirtual().name("integration-supervisor-start").start(() -> {
            try {
                for (IntegrationRuntime runtime : registered) {
                    if (launchIntegration(runtime)) {
                        publishStarted(runtime);
                    }
                }
                startFuture.complete(null);
            } catch (Throwable unexpected) {
                startFuture.completeExceptionally(unexpected);
            }
        });
        return startFuture;
    }

    @Override
    public void stop() {
        supervisorStopping = true;
        for (IntegrationRuntime runtime : reverseRegistrationOrder()) {
            stopRuntime(runtime, "supervisor stop");
        }
    }

    @Override
    public CompletableFuture<Void> startIntegration(IntegrationId id) {
        Objects.requireNonNull(id, "id");
        IntegrationRuntime runtime = requireRegistered(id);
        stateLock.lock();
        try {
            if (runtime.state != HealthState.FAILED) {
                throw new IllegalStateException(
                        "Integration '" + runtime.integrationType + "' is not FAILED (current "
                                + "state: " + runtime.state + "); manual start applies to FAILED "
                                + "integrations only");
            }
            // Manual restart resets the health counters (interface contract).
            runtime.shuttingDown = false;
            runtime.consecutiveFailures = 0;
            runtime.errorCount = 0;
            runtime.restartTimestamps.clear();
            runtime.detail = HealthDetail.NONE;
        } finally {
            stateLock.unlock();
        }
        return runOnSupervisorThread("integration-manual-start-" + runtime.integrationType, () -> {
            if (launchIntegration(runtime)) {
                transitionState(runtime, HealthState.HEALTHY, HealthDetail.NONE,
                        "manual restart");
                publishRestarted(runtime, HealthState.FAILED, restartCountSnapshot(runtime),
                        "manual restart");
            }
        });
    }

    @Override
    public CompletableFuture<Void> stopIntegration(IntegrationId id) {
        Objects.requireNonNull(id, "id");
        IntegrationRuntime runtime = requireRegistered(id);
        return runOnSupervisorThread("integration-stop-" + runtime.integrationType,
                () -> stopRuntime(runtime, "administrative stop"));
    }

    @Override
    public CompletableFuture<Void> restartIntegration(IntegrationId id) {
        Objects.requireNonNull(id, "id");
        IntegrationRuntime runtime = requireRegistered(id);
        return runOnSupervisorThread("integration-restart-" + runtime.integrationType, () -> {
            // Doc 05 §3.14 M9.1 slice: the flag is carried for the duration; the
            // suppression behaviors it gates are deferred supervisor breadth.
            HealthState previous = stateSnapshot(runtime);
            stateLock.lock();
            try {
                runtime.plannedRestart = true;
            } finally {
                stateLock.unlock();
            }
            try {
                stopRuntime(runtime, "planned restart");
                stateLock.lock();
                try {
                    runtime.shuttingDown = false;
                } finally {
                    stateLock.unlock();
                }
                if (launchIntegration(runtime)) {
                    if (previous != HealthState.HEALTHY) {
                        transitionState(runtime, HealthState.HEALTHY, HealthDetail.NONE,
                                "planned restart");
                    }
                    publishRestarted(runtime, previous, restartCountSnapshot(runtime),
                            "planned restart");
                }
            } finally {
                stateLock.lock();
                try {
                    runtime.plannedRestart = false;
                } finally {
                    stateLock.unlock();
                }
            }
        });
    }

    @Override
    public Optional<IntegrationHealthRecord> health(IntegrationId id) {
        Objects.requireNonNull(id, "id");
        stateLock.lock();
        try {
            return Optional.ofNullable(runtimes.get(id)).map(this::snapshotRecord);
        } finally {
            stateLock.unlock();
        }
    }

    @Override
    public Map<IntegrationId, IntegrationHealthRecord> allHealth() {
        stateLock.lock();
        try {
            Map<IntegrationId, IntegrationHealthRecord> snapshot = new LinkedHashMap<>();
            for (IntegrationRuntime runtime : runtimes.values()) {
                snapshot.put(runtime.id, snapshotRecord(runtime));
            }
            return Collections.unmodifiableMap(snapshot);
        } finally {
            stateLock.unlock();
        }
    }

    @Override
    public boolean isRunning(IntegrationId id) {
        Objects.requireNonNull(id, "id");
        stateLock.lock();
        try {
            IntegrationRuntime runtime = runtimes.get(id);
            // "Running" = the adapter is HOSTED (created, thread launched, not torn
            // down) in an actively-running health state. A cleanly-stopped adapter
            // keeps its last health state but is no longer hosted.
            return runtime != null && runtime.adapter != null
                    && (runtime.state == HealthState.HEALTHY
                            || runtime.state == HealthState.DEGRADED);
        } finally {
            stateLock.unlock();
        }
    }

    @Override
    public Set<IntegrationId> registeredIntegrations() {
        stateLock.lock();
        try {
            return Set.copyOf(runtimes.keySet());
        } finally {
            stateLock.unlock();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Router seam (package-private — consumed by CommandRoutingSubscriber)
    // ════════════════════════════════════════════════════════════════════════

    /** The routing view of one hosted adapter: the adapter + its command executor. */
    record RouteTarget(IntegrationAdapter adapter, ExecutorService commandExecutor,
                       String integrationType) {
    }

    /**
     * Resolves a dispatch target: present only when the integration is registered,
     * hosted, and actively running (HEALTHY/DEGRADED). Empty means the router
     * publishes {@code command_result("integration_unavailable")}.
     */
    Optional<RouteTarget> routeTarget(IntegrationId id) {
        stateLock.lock();
        try {
            IntegrationRuntime runtime = runtimes.get(id);
            if (runtime == null || runtime.adapter == null
                    || (runtime.state != HealthState.HEALTHY
                            && runtime.state != HealthState.DEGRADED)) {
                return Optional.empty();
            }
            return Optional.of(new RouteTarget(
                    runtime.adapter, runtime.commandExecutor, runtime.integrationType));
        } finally {
            stateLock.unlock();
        }
    }

    /** A {@code handle(...)} throw feeds the owning integration's error window (DP-5). */
    void recordHandlerError(IntegrationId id, Throwable failure) {
        stateLock.lock();
        try {
            IntegrationRuntime runtime = runtimes.get(id);
            if (runtime != null) {
                runtime.errorCount++;
            }
        } finally {
            stateLock.unlock();
        }
        LOG.warn("integration.handler_error_recorded: integration_id={} classification={}",
                id, ExceptionClassifier.classify(failure));
    }

    // ════════════════════════════════════════════════════════════════════════
    // HealthReporter write-throughs (package-private — SupervisorHealthReporter)
    // ════════════════════════════════════════════════════════════════════════

    void recordHeartbeat(IntegrationId id) {
        Instant now = clock.instant();
        stateLock.lock();
        try {
            IntegrationRuntime runtime = runtimes.get(id);
            if (runtime != null) {
                runtime.lastHeartbeat = now;
            }
        } finally {
            stateLock.unlock();
        }
    }

    void recordKeepalive(IntegrationId id, Instant lastSuccess) {
        stateLock.lock();
        try {
            IntegrationRuntime runtime = runtimes.get(id);
            if (runtime != null) {
                // The adapter-reported protocol-level timestamp, stored verbatim
                // (honest storage — HealthReporter.reportKeepalive's contract).
                runtime.lastKeepalive = lastSuccess;
            }
        } finally {
            stateLock.unlock();
        }
    }

    void recordReportedError(IntegrationId id, Throwable error) {
        stateLock.lock();
        try {
            IntegrationRuntime runtime = runtimes.get(id);
            if (runtime != null) {
                runtime.errorCount++;
            }
        } finally {
            stateLock.unlock();
        }
        LOG.debug("integration.error_reported: integration_id={} error={}", id, error.toString());
    }

    // ════════════════════════════════════════════════════════════════════════
    // Fast stop (package-private — reached via IntegrationSupervisorAssembly.abandon)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Ungraceful teardown for the composition root's crash-simulation path (W4):
     * interrupts every run thread and shuts the command executors down WITHOUT
     * grace-period waits and WITHOUT publishing {@code integration_stopped} — the
     * abandon path skips durability work by design. Adapter {@code close()} is
     * attempted fire-and-forget on detached virtual threads.
     */
    void abandon() {
        supervisorStopping = true;
        for (IntegrationRuntime runtime : reverseRegistrationOrder()) {
            Thread runThread;
            IntegrationAdapter adapter;
            stateLock.lock();
            try {
                runtime.shuttingDown = true;
                runThread = runtime.runThread;
                adapter = runtime.adapter;
                runtime.runThread = null;
                runtime.adapter = null;
            } finally {
                stateLock.unlock();
            }
            if (runThread != null) {
                runThread.interrupt();
            }
            shutdownCommandExecutor(runtime);
            if (adapter != null) {
                Thread.ofVirtual()
                        .name("integration-abandon-close-" + runtime.integrationType)
                        .start(() -> closeAdapterQuietly(runtime, adapter));
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Launch + supervision loop
    // ════════════════════════════════════════════════════════════════════════

    /**
     * create() → initialize() → launch run() on the DP-11 thread. Any create or
     * initialize failure marks the integration FAILED and returns {@code false}
     * — the caller continues with its remaining work (INV-RF-01).
     */
    private boolean launchIntegration(IntegrationRuntime runtime) {
        IntegrationAdapter adapter;
        try {
            adapter = runtime.factory.create(buildContext(runtime));
            adapter.initialize();
        } catch (PermanentIntegrationException | RuntimeException failure) {
            recordFailure(runtime);
            markFailed(runtime, HealthDetail.PERMANENT_FAILURE,
                    "Integration '" + runtime.integrationType + "' failed to start: "
                            + describe(failure), failure);
            return false;
        }
        Thread runThread = newRunThread(runtime);
        boolean shutdownRaced;
        stateLock.lock();
        try {
            shutdownRaced = runtime.shuttingDown || supervisorStopping;
            if (!shutdownRaced) {
                runtime.adapter = adapter;
                runtime.runThread = runThread;
                // shutdownNow() is irreversible — a relaunch after stopRuntime
                // (restartIntegration, stop→manual start) needs a fresh executor
                // or the restarted adapter could never receive commands again.
                if (runtime.commandExecutor.isShutdown()) {
                    runtime.commandExecutor =
                            IntegrationRuntime.newCommandExecutor(runtime.integrationType);
                }
            }
        } finally {
            stateLock.unlock();
        }
        if (shutdownRaced) {
            // stop()/abandon() raced the launch — do not host the adapter.
            closeAdapterQuietly(runtime, adapter);
            return false;
        }
        runThread.start();
        LOG.info("integration.launched: integration_id={} integration_type={} io_type={}",
                runtime.id, runtime.integrationType, runtime.descriptor.ioType());
        return true;
    }

    /**
     * DP-11 thread allocation: NETWORK adapters run on a virtual thread; SERIAL
     * adapters on a dedicated platform thread (jSerialComm JNI pins carriers —
     * the Zigbee adapter arrives SERIAL at M9.4). Both named
     * {@code integration-<type>-0}.
     */
    private Thread newRunThread(IntegrationRuntime runtime) {
        String name = "integration-" + runtime.integrationType + "-0";
        Thread.Builder builder = (runtime.descriptor.ioType() == IoType.SERIAL)
                ? Thread.ofPlatform().name(name)
                : Thread.ofVirtual().name(name);
        return builder.unstarted(() -> {
            try {
                superviseLoop(runtime);
            } finally {
                // A terminal loop exit un-hosts THIS thread (identity-guarded so a
                // newer generation's thread is never cleared by a dying zombie).
                // Without this, stop() would see a stale runThread on a FAILED
                // runtime and publish a spurious integration_stopped.
                clearRunThreadIfCurrent(runtime);
            }
        });
    }

    private void clearRunThreadIfCurrent(IntegrationRuntime runtime) {
        stateLock.lock();
        try {
            if (runtime.runThread == Thread.currentThread()) {
                runtime.runThread = null;
            }
        } finally {
            stateLock.unlock();
        }
    }

    /**
     * {@code true} while the calling thread is the runtime's CURRENT hosted run
     * thread. A thread that survived an interrupt past the join grace and was
     * superseded by a relaunch (restartIntegration) reads {@code false} here and
     * must exit without touching the FSM — otherwise it would un-host the fresh
     * generation's adapter (the zombie-loop hazard).
     */
    private boolean isCurrentRunThread(IntegrationRuntime runtime) {
        stateLock.lock();
        try {
            return runtime.runThread == Thread.currentThread();
        } finally {
            stateLock.unlock();
        }
    }

    /**
     * The per-adapter supervision loop, running ON the adapter's own thread:
     * {@code run()} → classify the exit (Doc 05 §3.7) → clean stop / FAILED /
     * clock-driven backoff restart (DP-10).
     */
    private void superviseLoop(IntegrationRuntime runtime) {
        while (true) {
            IntegrationAdapter adapter = adapterForCurrentThread(runtime);
            if (adapter == null) {
                return;             // un-hosted, or this thread was superseded
            }
            Throwable exit = null;
            try {
                adapter.run();
            } catch (Throwable failure) {
                exit = failure;
            }
            // Clear a pending interrupt so post-exit cleanup (bounded joins) is not
            // poisoned; the shuttingDown flag, not the interrupt bit, drives intent.
            Thread.interrupted();

            if (!isCurrentRunThread(runtime)) {
                // This thread survived an interrupt past the join grace and a newer
                // generation was launched (restartIntegration). It must NOT touch
                // the FSM or close anything hosted — die silently.
                return;
            }
            if (isShuttingDown(runtime)) {
                // Shutdown-aware reclassification (Doc 05 §3.7): whatever run()
                // threw while the per-adapter shuttingDown flag was set is the
                // supervisor's own doing. stopRuntime owns close + events.
                return;
            }

            ExceptionClassification classification = (exit == null)
                    ? ExceptionClassification.TRANSIENT     // unexpected loop return
                    : ExceptionClassifier.classify(exit);
            switch (classification) {
                case SHUTDOWN_SIGNAL -> {
                    cleanStopFromLoop(runtime, adapter);
                    return;
                }
                case PERMANENT, AUTH_FAILED -> {
                    // AUTH_FAILED's reauth-or-suspend routing (AMD-56) is deferred
                    // breadth; until then it fails like PERMANENT (never retried).
                    recordFailure(runtime);
                    IntegrationAdapter claimed = claimAdapter(runtime, adapter);
                    if (claimed == null) {
                        return;     // a concurrent stop claimed it — stop owns teardown
                    }
                    closeBounded(runtime, claimed);
                    markFailed(runtime, HealthDetail.PERMANENT_FAILURE,
                            "Integration '" + runtime.integrationType + "' run() failed "
                                    + "permanently: " + describe(exit), exit);
                    return;
                }
                case TRANSIENT -> {
                    if (!restartAfterBackoff(runtime, adapter, exit)) {
                        return;
                    }
                }
            }
        }
    }

    /**
     * The TRANSIENT restart cycle: intensity check against
     * {@code HealthParameters.maxRestarts/restartWindow}, exponential
     * descriptor-driven backoff (capped), then re-create + re-initialize.
     * Returns {@code true} when a fresh adapter is hosted and the loop should
     * re-enter {@code run()}.
     */
    private boolean restartAfterBackoff(IntegrationRuntime runtime, IntegrationAdapter adapter,
                                        Throwable exit) {
        recordFailure(runtime);
        Instant now = clock.instant();
        int restartsInWindow;
        boolean intensityExceeded;
        stateLock.lock();
        try {
            Duration window = runtime.descriptor.healthParameters().restartWindow();
            Instant windowStart = now.minus(window);
            while (!runtime.restartTimestamps.isEmpty()
                    && runtime.restartTimestamps.peekFirst().isBefore(windowStart)) {
                runtime.restartTimestamps.removeFirst();
            }
            intensityExceeded = runtime.restartTimestamps.size()
                    >= runtime.descriptor.healthParameters().maxRestarts();
            if (intensityExceeded) {
                restartsInWindow = runtime.restartTimestamps.size();
            } else {
                runtime.restartTimestamps.addLast(now);
                restartsInWindow = runtime.restartTimestamps.size();
            }
        } finally {
            stateLock.unlock();
        }

        IntegrationAdapter claimed = claimAdapter(runtime, adapter);
        if (claimed == null) {
            return false;           // a concurrent stop claimed it — stop owns teardown
        }
        closeBounded(runtime, claimed);

        if (intensityExceeded) {
            markFailed(runtime, HealthDetail.RESTART_LIMIT_EXCEEDED,
                    "Integration '" + runtime.integrationType + "' exceeded restart limit ("
                            + restartsInWindow + " restarts in "
                            + runtime.descriptor.healthParameters().restartWindow().toSeconds()
                            + " seconds): " + describe(exit), exit);
            return false;
        }

        Duration delay = backoffDelay(runtime.descriptor.backoffParameters(), restartsInWindow);
        LOG.warn("integration.transient_failure: integration_id={} integration_type={} "
                        + "restart_in_window={} backoff_ms={} cause={}",
                runtime.id, runtime.integrationType, restartsInWindow, delay.toMillis(),
                describe(exit));
        if (!waitUntilClockReaches(now.plus(delay), runtime)) {
            return false;               // shutdown raced the backoff wait
        }

        IntegrationAdapter fresh;
        try {
            fresh = runtime.factory.create(buildContext(runtime));
            fresh.initialize();
        } catch (PermanentIntegrationException | RuntimeException relaunchFailure) {
            recordFailure(runtime);
            markFailed(runtime, HealthDetail.PERMANENT_FAILURE,
                    "Integration '" + runtime.integrationType + "' failed to restart: "
                            + describe(relaunchFailure), relaunchFailure);
            return false;
        }
        boolean shutdownRaced;
        stateLock.lock();
        try {
            shutdownRaced = runtime.shuttingDown || supervisorStopping
                    || runtime.runThread != Thread.currentThread();
            if (!shutdownRaced) {
                runtime.adapter = fresh;
            }
        } finally {
            stateLock.unlock();
        }
        if (shutdownRaced) {
            closeAdapterQuietly(runtime, fresh);
            return false;
        }
        publishRestarted(runtime, stateSnapshot(runtime), restartsInWindow, describe(exit));
        return true;
    }

    /** An unsolicited SHUTDOWN_SIGNAL (interrupt outside supervisor stop) — clean stop. */
    private void cleanStopFromLoop(IntegrationRuntime runtime, IntegrationAdapter adapter) {
        IntegrationAdapter claimed = claimAdapter(runtime, adapter);
        if (claimed == null) {
            return;                 // a concurrent stop claimed it — stop owns teardown
        }
        closeBounded(runtime, claimed);
        publishStopped(runtime, "adapter shutdown signal");
    }

    // ════════════════════════════════════════════════════════════════════════
    // Stop machinery
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Stops one hosted adapter: set the {@code shuttingDown} flag, interrupt the
     * run thread, close bounded by the grace period (log abandonment on overrun,
     * proceed — Doc 05 §3.6), shut the command executor down, publish
     * {@code integration_stopped}. No-ops for an unhosted runtime — safe when
     * never started, already stopped, or FAILED.
     */
    private void stopRuntime(IntegrationRuntime runtime, String reason) {
        Thread runThread;
        IntegrationAdapter claimed;
        stateLock.lock();
        try {
            runtime.shuttingDown = true;
            runThread = runtime.runThread;
            claimed = runtime.adapter;      // the claim — exactly one party closes
            runtime.runThread = null;       // a still-alive loop reads "superseded" and dies
            runtime.adapter = null;
        } finally {
            stateLock.unlock();
        }
        shutdownCommandExecutor(runtime);
        if (claimed == null && runThread == null) {
            return;                     // never hosted (or already terminal) — no event
        }
        if (runThread != null) {
            runThread.interrupt();
            joinBounded(runThread);
        }
        if (claimed != null) {
            closeBounded(runtime, claimed);
            // Publish only when THIS call claimed the hosted adapter — a loop-owned
            // terminal path (crash/clean-stop mid-flight) publishes its own event.
            publishStopped(runtime, reason);
        }
    }

    private void shutdownCommandExecutor(IntegrationRuntime runtime) {
        stateLock.lock();
        try {
            runtime.commandExecutor.shutdownNow();
        } finally {
            stateLock.unlock();
        }
    }

    /**
     * Runs {@code close()} on a dedicated virtual thread bounded by the grace
     * period. On overrun: log the abandonment, interrupt the closer (best
     * effort), and proceed — a blocked close() must never hang shutdown.
     */
    private void closeBounded(IntegrationRuntime runtime, IntegrationAdapter adapter) {
        if (adapter == null) {
            return;
        }
        Thread closer = Thread.ofVirtual()
                .name("integration-close-" + runtime.integrationType)
                .start(() -> closeAdapterQuietly(runtime, adapter));
        try {
            if (!closer.join(closeGrace)) {
                LOG.warn("integration.close_abandoned: integration_id={} integration_type={} "
                                + "grace_ms={} — close() exceeded the grace period; proceeding",
                        runtime.id, runtime.integrationType, closeGrace.toMillis());
                closer.interrupt();
            }
        } catch (InterruptedException interrupted) {
            // The CALLER was interrupted, not the closer overrunning its grace —
            // do not punish a compliant close(): leave the closer finishing on its
            // own virtual thread and just stop waiting for it.
            Thread.currentThread().interrupt();
        }
    }

    private void closeAdapterQuietly(IntegrationRuntime runtime, IntegrationAdapter adapter) {
        try {
            adapter.close();
        } catch (RuntimeException closeFailure) {
            LOG.warn("integration.close_failed: integration_id={} integration_type={}",
                    runtime.id, runtime.integrationType, closeFailure);
        }
    }

    private void joinBounded(Thread thread) {
        try {
            if (!thread.join(closeGrace)) {
                LOG.warn("integration.run_thread_survived_interrupt: thread={} grace_ms={}",
                        thread.getName(), closeGrace.toMillis());
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // State + snapshots
    // ════════════════════════════════════════════════════════════════════════

    private void markFailed(IntegrationRuntime runtime, HealthDetail detail, String reason,
                            Throwable cause) {
        HealthState previous = transitionState(runtime, HealthState.FAILED, detail, reason);
        LOG.error("integration.failed: integration_id={} integration_type={} detail={} "
                        + "reason={}",
                runtime.id, runtime.integrationType, detail, reason, cause);
        publishLifecycle(EventTypes.INTEGRATION_HEALTH_CHANGED,
                new IntegrationHealthChanged(runtime.id, runtime.integrationType, previous,
                        HealthState.FAILED, reason, 0.0),
                runtime.id, EventPriority.CRITICAL);
    }

    /**
     * Applies a state transition under the lock and publishes the DP-9
     * {@code integration_health_changed} for it (CRITICAL on transitions to
     * FAILED, NORMAL otherwise) — except FAILED, whose publication
     * {@link #markFailed} owns. Returns the previous state.
     */
    private HealthState transitionState(IntegrationRuntime runtime, HealthState next,
                                        HealthDetail detail, String reason) {
        HealthState previous;
        stateLock.lock();
        try {
            previous = runtime.state;
            runtime.state = next;
            runtime.detail = detail;
            runtime.stateChangedAt = clock.instant();
        } finally {
            stateLock.unlock();
        }
        if (previous != next && next != HealthState.FAILED) {
            publishLifecycle(EventTypes.INTEGRATION_HEALTH_CHANGED,
                    new IntegrationHealthChanged(runtime.id, runtime.integrationType, previous,
                            next, reason, next == HealthState.HEALTHY ? 1.0 : 0.0),
                    runtime.id, EventPriority.NORMAL);
        }
        return previous;
    }

    private void recordFailure(IntegrationRuntime runtime) {
        stateLock.lock();
        try {
            runtime.consecutiveFailures++;
        } finally {
            stateLock.unlock();
        }
    }

    /**
     * Atomically claims the hosted adapter for closing IF it is still the
     * {@code expected} instance. Exactly one party (the supervise loop or
     * stopRuntime/abandon, whichever un-hosts first) closes an adapter —
     * {@code IntegrationAdapter.close()} promises sequential idempotency only,
     * not concurrent-invocation safety.
     */
    private IntegrationAdapter claimAdapter(IntegrationRuntime runtime,
                                            IntegrationAdapter expected) {
        stateLock.lock();
        try {
            if (runtime.adapter != expected) {
                return null;
            }
            runtime.adapter = null;
            return expected;
        } finally {
            stateLock.unlock();
        }
    }

    /**
     * The supervise loop's adapter read: present only while the calling thread
     * is the CURRENT hosted run thread (a superseded zombie reads null and dies).
     */
    private IntegrationAdapter adapterForCurrentThread(IntegrationRuntime runtime) {
        stateLock.lock();
        try {
            if (runtime.runThread != Thread.currentThread()) {
                return null;
            }
            return runtime.adapter;
        } finally {
            stateLock.unlock();
        }
    }

    private HealthState stateSnapshot(IntegrationRuntime runtime) {
        stateLock.lock();
        try {
            return runtime.state;
        } finally {
            stateLock.unlock();
        }
    }

    private int restartCountSnapshot(IntegrationRuntime runtime) {
        stateLock.lock();
        try {
            return runtime.restartTimestamps.size();
        } finally {
            stateLock.unlock();
        }
    }

    private boolean isShuttingDown(IntegrationRuntime runtime) {
        stateLock.lock();
        try {
            return runtime.shuttingDown || supervisorStopping;
        } finally {
            stateLock.unlock();
        }
    }

    private IntegrationRuntime requireRegistered(IntegrationId id) {
        stateLock.lock();
        try {
            IntegrationRuntime runtime = runtimes.get(id);
            if (runtime == null) {
                throw new IllegalArgumentException(
                        "Integration '" + id + "' is not registered");
            }
            return runtime;
        } finally {
            stateLock.unlock();
        }
    }

    private List<IntegrationRuntime> reverseRegistrationOrder() {
        List<IntegrationRuntime> ordered;
        stateLock.lock();
        try {
            ordered = new ArrayList<>(runtimes.values());
        } finally {
            stateLock.unlock();
        }
        Collections.reverse(ordered);
        return ordered;
    }

    /** Must be called under {@link #stateLock} (reads mutable runtime fields). */
    private IntegrationHealthRecord snapshotRecord(IntegrationRuntime runtime) {
        int windowSize = runtime.descriptor.healthParameters().healthWindowSize();
        int errorCount = Math.min(runtime.errorCount, windowSize);
        SlidingWindow errorWindow = new SlidingWindow(windowSize, errorCount,
                windowSize == 0 ? 0.0 : (double) errorCount / windowSize);
        // M9.1 tracks only the error window; timeout/slow-call stay empty and the
        // health score is the binary slice value (full formula = deferred breadth).
        SlidingWindow empty = new SlidingWindow(windowSize, 0, 0.0);
        double healthScore = runtime.state == HealthState.FAILED ? 0.0 : 1.0;
        return new IntegrationHealthRecord(
                runtime.id,
                runtime.state,
                runtime.detail,
                healthScore,
                runtime.lastHeartbeat,
                runtime.lastKeepalive,
                runtime.stateChangedAt,
                runtime.consecutiveFailures,
                0,
                Duration.ZERO,
                errorWindow,
                empty,
                empty,
                runtime.plannedRestart);
    }

    // ════════════════════════════════════════════════════════════════════════
    // Backoff (W8 — clock-driven, interrupt-safe)
    // ════════════════════════════════════════════════════════════════════════

    private static Duration backoffDelay(BackoffParameters parameters, int attempt) {
        double scaledMillis = parameters.initialDelay().toMillis()
                * Math.pow(parameters.multiplier(), Math.max(attempt - 1, 0));
        long cappedMillis = (long) Math.min(scaledMillis, parameters.maxDelay().toMillis());
        return Duration.ofMillis(Math.max(cappedMillis, 0L));
    }

    /**
     * Waits until the injected clock reaches {@code deadline}, re-checking on
     * short interrupt-safe quanta (W8). Returns {@code false} when shutdown or
     * an interrupt raced the wait.
     */
    private boolean waitUntilClockReaches(Instant deadline, IntegrationRuntime runtime) {
        while (clock.instant().isBefore(deadline)) {
            if (isShuttingDown(runtime)) {
                return false;
            }
            try {
                Thread.sleep(WAIT_QUANTUM_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return !isShuttingDown(runtime);
    }

    // ════════════════════════════════════════════════════════════════════════
    // Context + events
    // ════════════════════════════════════════════════════════════════════════

    /**
     * DP-12 context composition (M9.1 slice): the shared publisher/registries,
     * a per-integration {@link SupervisorHealthReporter}, per-integration-scoped
     * config access from the injected factory (the B7 preferred path), and the
     * 5 service-gated tails null (the fake declares no RequiredService; real
     * SCHEDULER/TELEMETRY wiring arrives with the Zigbee descriptor at M9.4).
     */
    private IntegrationContext buildContext(IntegrationRuntime runtime) {
        return new IntegrationContext(
                runtime.id,
                runtime.integrationType,
                publisher,
                entityRegistry,
                stateQueryService,
                new SupervisorHealthReporter(this, runtime.id),
                configAccessFactory.apply(runtime.integrationType),
                null, null, null, null, null);
    }

    private void publishStarted(IntegrationRuntime runtime) {
        publishLifecycle(EventTypes.INTEGRATION_STARTED,
                new IntegrationStarted(runtime.id, runtime.integrationType,
                        HealthState.HEALTHY, "started"),
                runtime.id, EventPriority.NORMAL);
    }

    private void publishStopped(IntegrationRuntime runtime, String reason) {
        // A clean stop does not change HealthState (the AMD-58 same-state
        // convention) — the adapter is simply no longer hosted; isRunning()
        // reflects that through the hosted-adapter check.
        HealthState state = stateSnapshot(runtime);
        publishLifecycle(EventTypes.INTEGRATION_STOPPED,
                new IntegrationStopped(runtime.id, runtime.integrationType, state, state, reason),
                runtime.id, EventPriority.NORMAL);
    }

    private void publishRestarted(IntegrationRuntime runtime, HealthState previous,
                                  int restartCount, String reason) {
        publishLifecycle(EventTypes.INTEGRATION_RESTARTED,
                new IntegrationRestarted(runtime.id, runtime.integrationType, previous,
                        HealthState.HEALTHY, reason, restartCount),
                runtime.id, EventPriority.NORMAL);
    }

    /**
     * DP-9: lifecycle events publish over the EXISTING integration-api payload
     * records as causal roots ({@code publishRoot}), subject
     * {@code SubjectRef.integration(id)}, origin SYSTEM. Zero event types minted.
     */
    private void publishLifecycle(String eventType, DomainEvent payload, IntegrationId id,
                                  EventPriority priority) {
        EventDraft draft = new EventDraft(eventType, SCHEMA_VERSION, null,
                SubjectRef.integration(id), priority, EventOrigin.SYSTEM, payload, null, null);
        try {
            publisher.publishRoot(draft);
        } catch (SequenceConflictException conflict) {
            LOG.error("Failed to publish {} for integration {}: sequence conflict",
                    eventType, id, conflict);
        }
    }

    private CompletableFuture<Void> runOnSupervisorThread(String threadName, Runnable task) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        Thread.ofVirtual().name(threadName).start(() -> {
            try {
                task.run();
                future.complete(null);
            } catch (Throwable failure) {
                future.completeExceptionally(failure);
            }
        });
        return future;
    }

    private static String describe(Throwable failure) {
        if (failure == null) {
            return "run() returned without a shutdown signal";
        }
        String message = failure.getMessage();
        return failure.getClass().getSimpleName() + (message != null ? ": " + message : "");
    }

    // ════════════════════════════════════════════════════════════════════════
    // Per-integration runtime state (guarded by stateLock)
    // ════════════════════════════════════════════════════════════════════════

    private static final class IntegrationRuntime {

        final IntegrationId id;
        final String integrationType;
        final IntegrationDescriptor descriptor;
        final IntegrationFactory factory;
        /**
         * Single-threaded per-adapter command executor (Doc 08 §3.10 step 1):
         * FIFO per adapter, virtual thread named {@code integration-cmd-<type>}.
         * Survives TRANSIENT restart cycles; shut down at stop/abandon and
         * RECREATED by the next relaunch (restartIntegration / manual start) —
         * {@code shutdownNow()} is irreversible, so a restarted adapter needs a
         * fresh executor or it could never receive commands again. Guarded by
         * the supervisor's state lock.
         */
        ExecutorService commandExecutor;
        final ArrayDeque<Instant> restartTimestamps = new ArrayDeque<>();

        IntegrationAdapter adapter;
        Thread runThread;
        HealthState state = HealthState.HEALTHY;
        HealthDetail detail = HealthDetail.NONE;
        Instant lastHeartbeat;
        Instant lastKeepalive;              // nullable by contract — null until reported
        Instant stateChangedAt;
        int consecutiveFailures;
        int errorCount;
        boolean plannedRestart;
        boolean shuttingDown;

        IntegrationRuntime(IntegrationId id, IntegrationDescriptor descriptor,
                           IntegrationFactory factory, Instant registeredAt) {
            this.id = id;
            this.integrationType = descriptor.integrationType();
            this.descriptor = descriptor;
            this.factory = factory;
            this.commandExecutor = newCommandExecutor(descriptor.integrationType());
            this.lastHeartbeat = registeredAt;
            this.stateChangedAt = registeredAt;
        }

        static ExecutorService newCommandExecutor(String integrationType) {
            return Executors.newSingleThreadExecutor(
                    Thread.ofVirtual()
                            .name("integration-cmd-" + integrationType + "-", 0)
                            .factory());
        }
    }
}
