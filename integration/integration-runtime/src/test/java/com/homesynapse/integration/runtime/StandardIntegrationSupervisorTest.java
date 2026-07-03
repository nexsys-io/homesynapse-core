/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.event.test.InMemoryEventStore;
import com.homesynapse.integration.BackoffParameters;
import com.homesynapse.integration.DataPath;
import com.homesynapse.integration.HealthParameters;
import com.homesynapse.integration.HealthState;
import com.homesynapse.integration.IntegrationAdapter;
import com.homesynapse.integration.IntegrationContext;
import com.homesynapse.integration.IntegrationDescriptor;
import com.homesynapse.integration.IntegrationFactory;
import com.homesynapse.integration.IoType;
import com.homesynapse.integration.IntegrationHealthChanged;
import com.homesynapse.integration.IntegrationRestarted;
import com.homesynapse.integration.IntegrationStarted;
import com.homesynapse.integration.IntegrationStopped;
import com.homesynapse.integration.PermanentIntegrationException;
import com.homesynapse.integration.test.StubIntegrationContext;
import com.homesynapse.integration.test.TestAdapter;
import com.homesynapse.platform.identity.IntegrationId;
import com.homesynapse.test.TestClock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * T1–T8 — the {@link StandardIntegrationSupervisor} unit slice: registration,
 * DP-11 thread launch, lifecycle-event publication, the DP-10 minimal health
 * FSM (HEALTHY ↔ restart cycle → FAILED), stop() grace semantics, and the
 * {@link SupervisorHealthReporter} write-through surface.
 *
 * <p>Time is injected via {@link TestClock} (§4c — no direct time access in
 * test code). Restart backoff is driven by stepping the clock (W8 — the
 * supervisor's backoff wait re-checks {@code clock.instant()} against its
 * deadline on short interrupt-safe quanta, so a stepped clock advances it
 * deterministically); the real-time poll helpers below are clock-independent
 * and bounded.</p>
 */
@DisplayName("StandardIntegrationSupervisor -- registration, FSM slice, lifecycle events (M9.1)")
final class StandardIntegrationSupervisorTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration TEST_GRACE = Duration.ofMillis(200);

    private TestClock clock;
    private InMemoryEventStore store;
    private IntegrationContext stubContext;
    private StandardIntegrationSupervisor supervisor;

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    StandardIntegrationSupervisorTest() {
    }

    @BeforeEach
    void setUp() {
        clock = TestClock.at(T0);
        store = new InMemoryEventStore(clock);
        stubContext = StubIntegrationContext.defaults();
        supervisor = new StandardIntegrationSupervisor(
                store,
                stubContext.entityRegistry(),
                stubContext.stateQueryService(),
                type -> stubContext.configAccess(),
                clock,
                TEST_GRACE);
    }

    @AfterEach
    void tearDown() {
        supervisor.stop();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Tests
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("T1: start registers + launches; integration_started published on the "
            + "integration subject; isRunning true; registeredIntegrations carries the derived id")
    void start_registersLaunchesAndPublishesStarted() throws Exception {
        RecordingFactory factory = RecordingFactory.blocking("test-a");

        supervisor.start(List.of(factory)).get(5, TimeUnit.SECONDS);

        IntegrationId derivedId = IntegrationIds.deriveStable("test-a");
        assertThat(supervisor.registeredIntegrations()).containsExactly(derivedId);
        assertThat(supervisor.isRunning(derivedId)).isTrue();

        List<EventEnvelope> started = eventsOfType(EventTypes.INTEGRATION_STARTED);
        assertThat(started).hasSize(1);
        assertThat(started.get(0).payload()).isInstanceOf(IntegrationStarted.class);
        IntegrationStarted payload = (IntegrationStarted) started.get(0).payload();
        assertThat(payload.integrationId()).isEqualTo(derivedId);
        assertThat(payload.newState()).isEqualTo(HealthState.HEALTHY);
        assertThat(started.get(0).subjectRef()).isEqualTo(SubjectRef.integration(derivedId));
    }

    @Test
    @DisplayName("T2: duplicate integrationType at start throws IllegalArgumentException "
            + "naming the duplicate")
    void start_withDuplicateType_throwsNamingTheDuplicate() {
        RecordingFactory first = RecordingFactory.blocking("type-x");
        RecordingFactory second = RecordingFactory.blocking("type-x");

        assertThatThrownBy(() -> supervisor.start(List.of(first, second)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("type-x");
    }

    @Test
    @DisplayName("T3: descriptor() purity honored — create/initialize called exactly once "
            + "each, in order, for a healthy launch")
    void start_callsCreateThenInitializeExactlyOnce() throws Exception {
        RecordingFactory factory = RecordingFactory.blocking("test-order");

        supervisor.start(List.of(factory)).get(5, TimeUnit.SECONDS);

        assertThat(factory.descriptorCalls()).isEqualTo(1);
        assertThat(factory.createCalls()).isEqualTo(1);
        assertThat(factory.currentAdapter().initializeCount()).isEqualTo(1);
        assertThat(factory.callOrder()).containsExactly("create", "initialize");
    }

    @Test
    @DisplayName("T4: stop() proceeds in reverse registration order, publishes "
            + "integration_stopped for each adapter, and abandons a close() that blocks "
            + "past the grace period without hanging")
    void stop_reverseOrderWithGracePeriodAbandonment() throws Exception {
        RecordingFactory factoryA = RecordingFactory.blocking("type-a");
        // type-b's close() blocks until interrupted — well past the 200 ms test grace.
        CountDownLatch closeBlock = new CountDownLatch(1);
        RecordingFactory factoryB = RecordingFactory.custom("type-b", () -> TestAdapter.builder()
                .onRun(StandardIntegrationSupervisorTest::blockUntilInterrupted)
                .onClose(() -> {
                    try {
                        closeBlock.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                })
                .build());
        supervisor.start(List.of(factoryA, factoryB)).get(5, TimeUnit.SECONDS);

        supervisor.stop();

        List<EventEnvelope> stopped = eventsOfType(EventTypes.INTEGRATION_STOPPED);
        assertThat(stopped).hasSize(2);
        // Reverse registration order: type-b (registered second) stops first.
        assertThat(((IntegrationStopped) stopped.get(0).payload()).integrationType())
                .isEqualTo("type-b");
        assertThat(((IntegrationStopped) stopped.get(1).payload()).integrationType())
                .isEqualTo("type-a");
        assertThat(supervisor.isRunning(IntegrationIds.deriveStable("type-a"))).isFalse();
        assertThat(supervisor.isRunning(IntegrationIds.deriveStable("type-b"))).isFalse();
    }

    @Test
    @DisplayName("T5: startIntegration on a non-FAILED integration throws "
            + "IllegalStateException (manual restart is FAILED-only)")
    void startIntegration_onNonFailed_throws() throws Exception {
        RecordingFactory factory = RecordingFactory.blocking("test-manual");
        supervisor.start(List.of(factory)).get(5, TimeUnit.SECONDS);
        IntegrationId id = IntegrationIds.deriveStable("test-manual");

        assertThatThrownBy(() -> supervisor.startIntegration(id))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("T6: run() throwing RuntimeException classifies TRANSIENT and restarts with "
            + "clock-driven backoff (integration_restarted); exceeding maxRestarts within "
            + "restartWindow escalates to FAILED with a CRITICAL integration_health_changed")
    void runThrowing_transientRestartsThenFailsAtIntensityLimit() throws Exception {
        // maxRestarts=2 within a 1h window; backoff 1s -> 2s (x2, capped 4s). Every run()
        // throws, so: crash -> restart#1 -> crash -> restart#2 -> crash -> intensity
        // exceeded -> FAILED. The stepped clock drives each backoff deadline.
        HealthParameters health = new HealthParameters(
                Duration.ofSeconds(120), 20, Duration.ofMinutes(5), Duration.ofHours(1),
                5, 2, Duration.ofHours(1), Duration.ofSeconds(30), Duration.ofMinutes(5), 3, 2);
        BackoffParameters backoff = new BackoffParameters(
                Duration.ofSeconds(1), 2.0, Duration.ofSeconds(4));
        RecordingFactory factory = RecordingFactory.throwing(
                "test-crash", health, backoff, () -> new IllegalStateException("boom"));
        IntegrationId id = IntegrationIds.deriveStable("test-crash");

        supervisor.start(List.of(factory)).get(5, TimeUnit.SECONDS);

        // Step the clock in sub-window increments until the FSM lands on FAILED.
        awaitWhileSteppingClock(
                () -> supervisor.health(id).map(h -> h.state() == HealthState.FAILED).orElse(false),
                Duration.ofMillis(500));

        assertThat(eventsOfType(EventTypes.INTEGRATION_RESTARTED)).hasSize(2);
        IntegrationHealthRecord record = supervisor.health(id).orElseThrow();
        assertThat(record.state()).isEqualTo(HealthState.FAILED);
        assertThat(record.detail()).isEqualTo(HealthDetail.RESTART_LIMIT_EXCEEDED);
        assertThat(supervisor.isRunning(id)).isFalse();

        List<EventEnvelope> healthChanged = eventsOfType(EventTypes.INTEGRATION_HEALTH_CHANGED);
        assertThat(healthChanged).isNotEmpty();
        EventEnvelope failedTransition = healthChanged.get(healthChanged.size() - 1);
        assertThat(failedTransition.priority()).isEqualTo(EventPriority.CRITICAL);
        assertThat(((IntegrationHealthChanged) failedTransition.payload()).newState())
                .isEqualTo(HealthState.FAILED);

        // The restart events carry the running restart count within the window.
        List<EventEnvelope> restarted = eventsOfType(EventTypes.INTEGRATION_RESTARTED);
        assertThat(((IntegrationRestarted) restarted.get(0).payload()).restartCount()).isEqualTo(1);
        assertThat(((IntegrationRestarted) restarted.get(1).payload()).restartCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("T7: create()/initialize() PermanentIntegrationException fails that "
            + "integration immediately (FAILED, CRITICAL health event) while siblings launch "
            + "unaffected (INV-RF-01)")
    void permanentInitializeFailure_failsOnlyThatIntegration() throws Exception {
        RecordingFactory failing = RecordingFactory.custom("type-broken",
                () -> TestAdapter.failing("wrong hardware revision"));
        RecordingFactory healthy = RecordingFactory.blocking("type-ok");

        supervisor.start(List.of(failing, healthy)).get(5, TimeUnit.SECONDS);

        IntegrationId brokenId = IntegrationIds.deriveStable("type-broken");
        IntegrationId okId = IntegrationIds.deriveStable("type-ok");
        IntegrationHealthRecord broken = supervisor.health(brokenId).orElseThrow();
        assertThat(broken.state()).isEqualTo(HealthState.FAILED);
        assertThat(broken.detail()).isEqualTo(HealthDetail.PERMANENT_FAILURE);
        assertThat(supervisor.isRunning(brokenId)).isFalse();
        assertThat(supervisor.isRunning(okId)).isTrue();
        assertThat(supervisor.registeredIntegrations()).containsExactlyInAnyOrder(brokenId, okId);

        List<EventEnvelope> healthChanged = eventsOfType(EventTypes.INTEGRATION_HEALTH_CHANGED);
        assertThat(healthChanged).hasSize(1);
        assertThat(healthChanged.get(0).priority()).isEqualTo(EventPriority.CRITICAL);
        // The failed sibling never publishes integration_started; the healthy one does.
        List<EventEnvelope> started = eventsOfType(EventTypes.INTEGRATION_STARTED);
        assertThat(started).hasSize(1);
        assertThat(((IntegrationStarted) started.get(0).payload()).integrationType())
                .isEqualTo("type-ok");
    }

    @Test
    @DisplayName("restartIntegration re-hosts a WORKING command route — the per-adapter "
            + "command executor is recreated after the stop half shut it down (regression: "
            + "a restarted adapter must still receive commands)")
    void restartIntegration_rehostsAWorkingCommandRoute() throws Exception {
        RecordingFactory factory = RecordingFactory.blocking("test-restart");
        supervisor.start(List.of(factory)).get(5, TimeUnit.SECONDS);
        IntegrationId id = IntegrationIds.deriveStable("test-restart");

        supervisor.restartIntegration(id).get(5, TimeUnit.SECONDS);

        assertThat(supervisor.isRunning(id)).isTrue();
        assertThat(eventsOfType(EventTypes.INTEGRATION_RESTARTED)).hasSize(1);
        assertThat(eventsOfType(EventTypes.INTEGRATION_STOPPED)).hasSize(1);   // the stop half
        // The route target must carry a LIVE executor — shutdownNow() is
        // irreversible, so the relaunch recreates it.
        StandardIntegrationSupervisor.RouteTarget target =
                supervisor.routeTarget(id).orElseThrow();
        assertThat(target.commandExecutor().isShutdown()).isFalse();
        CountDownLatch executed = new CountDownLatch(1);
        target.commandExecutor().execute(executed::countDown);
        assertThat(executed.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("T8: health snapshots carry the SupervisorHealthReporter write-throughs — "
            + "heartbeat from the injected clock, lastKeepalive null until reported")
    void healthSnapshots_reflectReporterWriteThroughs() throws Exception {
        RecordingFactory factory = RecordingFactory.blocking("test-health");
        supervisor.start(List.of(factory)).get(5, TimeUnit.SECONDS);
        IntegrationId id = IntegrationIds.deriveStable("test-health");

        // lastKeepalive is nullable by contract: null until the adapter reports one.
        IntegrationHealthRecord before = supervisor.health(id).orElseThrow();
        assertThat(before.lastKeepalive()).isNull();
        assertThat(before.lastHeartbeat()).isEqualTo(T0);

        clock.advance(Duration.ofSeconds(30));
        Instant heartbeatAt = clock.peek();
        factory.capturedContext().healthReporter().reportHeartbeat();

        Instant keepaliveAt = T0.plusSeconds(12);
        factory.capturedContext().healthReporter().reportKeepalive(keepaliveAt);

        IntegrationHealthRecord after = supervisor.health(id).orElseThrow();
        assertThat(after.lastHeartbeat()).isEqualTo(heartbeatAt);
        assertThat(after.lastKeepalive()).isEqualTo(keepaliveAt);

        // reportError feeds the error window count (honest storage, no evaluation).
        factory.capturedContext().healthReporter().reportError(new IllegalStateException("blip"));
        assertThat(supervisor.health(id).orElseThrow().errorWindow().count()).isEqualTo(1);
        assertThat(supervisor.allHealth()).containsKey(id);
    }

    // ════════════════════════════════════════════════════════════════════════
    // Harness
    // ════════════════════════════════════════════════════════════════════════

    private List<EventEnvelope> eventsOfType(String eventType) {
        return store.readByType(eventType, 0L, 1000).events();
    }

    /**
     * Polls {@code condition} while stepping the injected clock by {@code step} each
     * iteration — the W8 pattern: backoff deadlines are clock-driven, so the test
     * advances virtual time in sub-restartWindow increments and lets the supervisor's
     * short real-time wait quanta observe it. Bounded and clock-independent.
     */
    private void awaitWhileSteppingClock(BooleanSupplier condition, Duration step) {
        for (int poll = 0; poll < 500; poll++) {       // ~10s at 20ms
            if (condition.getAsBoolean()) {
                return;
            }
            clock.advance(step);
            sleepBriefly();
        }
        throw new AssertionError("condition not reached while stepping the test clock");
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(20L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while awaiting supervisor state", ex);
        }
    }

    private static void blockUntilInterrupted() {
        CountDownLatch never = new CountDownLatch(1);
        try {
            never.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Test-local {@link IntegrationFactory} wrapping the integration-api
     * {@link TestAdapter} fixture (no factory fixture exists — this records the
     * factory-side call pattern the fixtures cannot).
     */
    static final class RecordingFactory implements IntegrationFactory {

        private final String integrationType;
        private final HealthParameters healthParameters;
        private final BackoffParameters backoffParameters;
        private final Supplier<TestAdapter> adapterSupplier;
        private final List<String> callOrder = new CopyOnWriteArrayList<>();
        private final AtomicReference<TestAdapter> currentAdapter = new AtomicReference<>();
        private final AtomicReference<IntegrationContext> capturedContext = new AtomicReference<>();
        private volatile int descriptorCalls;
        private volatile int createCalls;

        private RecordingFactory(String integrationType, HealthParameters healthParameters,
                                 BackoffParameters backoffParameters,
                                 Supplier<TestAdapter> adapterSupplier) {
            this.integrationType = integrationType;
            this.healthParameters = healthParameters;
            this.backoffParameters = backoffParameters;
            this.adapterSupplier = adapterSupplier;
        }

        /** A factory whose adapter blocks in run() until interrupted (a healthy adapter). */
        static RecordingFactory blocking(String integrationType) {
            return custom(integrationType, () -> TestAdapter.builder()
                    .onRun(StandardIntegrationSupervisorTest::blockUntilInterrupted)
                    .build());
        }

        /** A factory whose adapter's run() always throws {@code failure.get()}. */
        static RecordingFactory throwing(String integrationType, HealthParameters health,
                                         BackoffParameters backoff,
                                         Supplier<RuntimeException> failure) {
            return new RecordingFactory(integrationType, health, backoff,
                    () -> TestAdapter.builder()
                            .onRun(() -> {
                                throw failure.get();
                            })
                            .build());
        }

        static RecordingFactory custom(String integrationType, Supplier<TestAdapter> supplier) {
            return new RecordingFactory(integrationType, HealthParameters.defaults(),
                    BackoffParameters.defaults(), supplier);
        }

        @Override
        public IntegrationDescriptor descriptor() {
            descriptorCalls++;
            return new IntegrationDescriptor(
                    integrationType, "Recording " + integrationType, IoType.NETWORK,
                    Set.of(), Set.of(DataPath.DOMAIN), healthParameters, Set.of(),
                    1, 1, 0, Set.of(), backoffParameters,
                    com.homesynapse.integration.IsolationLevel.IN_JVM, null);
        }

        @Override
        public IntegrationAdapter create(IntegrationContext context)
                throws PermanentIntegrationException {
            createCalls++;
            capturedContext.set(context);
            callOrder.add("create");
            TestAdapter inner = adapterSupplier.get();
            currentAdapter.set(inner);
            // Wrap so initialize() lands in the shared order log (create -> initialize).
            return new IntegrationAdapter() {
                @Override
                public void initialize() throws PermanentIntegrationException {
                    callOrder.add("initialize");
                    inner.initialize();
                }

                @Override
                public void run() throws Exception {
                    inner.run();
                }

                @Override
                public void close() {
                    inner.close();
                }

                @Override
                public com.homesynapse.integration.CommandHandler commandHandler() {
                    return inner.commandHandler();
                }
            };
        }

        int descriptorCalls() {
            return descriptorCalls;
        }

        int createCalls() {
            return createCalls;
        }

        List<String> callOrder() {
            return callOrder;
        }

        TestAdapter currentAdapter() {
            return currentAdapter.get();
        }

        IntegrationContext capturedContext() {
            return capturedContext.get();
        }
    }
}
