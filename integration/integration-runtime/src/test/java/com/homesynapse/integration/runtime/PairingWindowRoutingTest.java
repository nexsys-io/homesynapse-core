/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.homesynapse.event.test.InMemoryEventStore;
import com.homesynapse.integration.BackoffParameters;
import com.homesynapse.integration.CommandHandler;
import com.homesynapse.integration.DataPath;
import com.homesynapse.integration.HealthParameters;
import com.homesynapse.integration.IntegrationAdapter;
import com.homesynapse.integration.IntegrationContext;
import com.homesynapse.integration.IntegrationDescriptor;
import com.homesynapse.integration.IntegrationFactory;
import com.homesynapse.integration.IoType;
import com.homesynapse.integration.IsolationLevel;
import com.homesynapse.integration.PairingWindow;
import com.homesynapse.integration.PairingWindowControl;
import com.homesynapse.integration.PairingWindowRequest;
import com.homesynapse.integration.PermanentIntegrationException;
import com.homesynapse.integration.test.StubIntegrationContext;
import com.homesynapse.integration.test.TestAdapter;
import com.homesynapse.platform.identity.IntegrationId;
import com.homesynapse.platform.identity.Ulid;
import com.homesynapse.test.TestClock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * T5 (PJ-2) — {@link IntegrationSupervisor#openPairingWindow}: the open runs on the
 * hosted adapter's single-threaded command executor (the M9.4a command write path's
 * thread — never the caller's), and every failure is the FUTURE's, never a synchronous
 * throw: a non-running id → {@link IllegalStateException}; an adapter without
 * {@link PairingWindowControl} → {@link UnsupportedOperationException}; a shut-down
 * executor → {@link IllegalStateException} (the {@code RejectedExecutionException}
 * catch); an adapter throw → that throw, and it never feeds the health error window
 * (the window is not a command — {@code recordHandlerError} is the router's alone).
 *
 * <p>Time is injected via {@link TestClock} (§4c — no direct time access in test
 * code); the futures are awaited on bounded real-time waits.</p>
 */
@DisplayName("StandardIntegrationSupervisor -- openPairingWindow routing (PJ-2)")
final class PairingWindowRoutingTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration TEST_GRACE = Duration.ofMillis(200);
    private static final PairingWindowRequest REQUEST =
            new PairingWindowRequest(120, "pair the hallway sensor", "key-01", null);

    private TestClock clock;
    private InMemoryEventStore store;
    private IntegrationContext stubContext;
    private StandardIntegrationSupervisor supervisor;

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    PairingWindowRoutingTest() {
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

    @Test
    @DisplayName("T5a: a running adapter with PairingWindowControl opens on ITS command "
            + "executor thread (integration-cmd-<type>-N), never the caller's; the future "
            + "completes with the adapter's window")
    void openPairingWindow_runsOnCommandExecutor() throws Exception {
        WindowFactory factory = WindowFactory.withControl("pairing", clock);
        supervisor.start(List.of(factory)).get(5, TimeUnit.SECONDS);
        IntegrationId id = IntegrationIds.deriveStable("pairing");

        CompletableFuture<PairingWindow> future = supervisor.openPairingWindow(id, REQUEST);
        PairingWindow window = future.get(5, TimeUnit.SECONDS);

        assertThat(window.integrationId()).isEqualTo(id);
        assertThat(window.durationSeconds()).isEqualTo(120);
        assertThat(window.reason()).isEqualTo("pair the hallway sensor");
        assertThat(window.actor()).isEqualTo("key-01");
        assertThat(window.opensAt()).isEqualTo(T0);
        assertThat(window.closesAt()).isEqualTo(T0.plusSeconds(120));
        assertThat(factory.adapter().openedOn())
                .as("the open ran on the adapter's single-threaded command executor")
                .startsWith("integration-cmd-pairing-")
                .isNotEqualTo(Thread.currentThread().getName());
        assertThat(factory.adapter().openCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("T5b: an unknown (never registered) id fails the future with "
            + "IllegalStateException('integration not running') — no synchronous throw")
    void openPairingWindow_unknownId_failsWithIllegalState() throws Exception {
        supervisor.start(List.of(WindowFactory.withControl("pairing", clock)))
                .get(5, TimeUnit.SECONDS);
        IntegrationId unknown = IntegrationId.of(Ulid.parse("01H8000000000000000000000Z"));

        CompletableFuture<PairingWindow> future = supervisor.openPairingWindow(unknown, REQUEST);

        assertThat(future).isCompletedExceptionally();
        assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("integration not running: " + unknown);
    }

    @Test
    @DisplayName("T5c: a running adapter WITHOUT PairingWindowControl fails the future with "
            + "UnsupportedOperationException naming the integration type")
    void openPairingWindow_adapterWithoutControl_failsWithUnsupported() throws Exception {
        supervisor.start(List.of(WindowFactory.withoutControl("plain")))
                .get(5, TimeUnit.SECONDS);
        IntegrationId id = IntegrationIds.deriveStable("plain");
        assertThat(supervisor.isRunning(id)).isTrue();

        CompletableFuture<PairingWindow> future = supervisor.openPairingWindow(id, REQUEST);

        assertThat(future).isCompletedExceptionally();
        assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("plain has no pairing window");
    }

    @Test
    @DisplayName("T5d: a shut-down command executor fails the future with "
            + "IllegalStateException('integration stopping') — the RejectedExecutionException "
            + "is caught, never thrown at the caller")
    void openPairingWindow_shutDownExecutor_failsTheFuture() throws Exception {
        WindowFactory factory = WindowFactory.withControl("pairing", clock);
        supervisor.start(List.of(factory)).get(5, TimeUnit.SECONDS);
        IntegrationId id = IntegrationIds.deriveStable("pairing");
        // The route is still present (HEALTHY + hosted) but its executor is gone — the
        // supplyAsync submit is what throws RejectedExecutionException, synchronously.
        supervisor.routeTarget(id).orElseThrow().commandExecutor().shutdownNow();

        CompletableFuture<PairingWindow> future = supervisor.openPairingWindow(id, REQUEST);

        assertThat(future).isCompletedExceptionally();
        assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("integration stopping: " + id);
        assertThat(factory.adapter().openCount()).isZero();
    }

    @Test
    @DisplayName("T5e: an adapter throw inside the open completes the future exceptionally "
            + "with THAT throw and never feeds the health error window (not a command)")
    void openPairingWindow_adapterThrow_failsTheFuture_neverRecordsHandlerError()
            throws Exception {
        WindowFactory factory = WindowFactory.throwing("pairing",
                () -> new IllegalStateException("NCP NAK: setPolicy(trustCenterPolicy)"));
        supervisor.start(List.of(factory)).get(5, TimeUnit.SECONDS);
        IntegrationId id = IntegrationIds.deriveStable("pairing");

        CompletableFuture<PairingWindow> future = supervisor.openPairingWindow(id, REQUEST);

        assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("NCP NAK: setPolicy(trustCenterPolicy)");
        assertThat(supervisor.health(id).orElseThrow().errorWindow().count())
                .as("the window is not a command: no handler error is recorded")
                .isZero();
        assertThat(supervisor.isRunning(id)).isTrue();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Harness
    // ════════════════════════════════════════════════════════════════════════

    /** A hosted adapter that blocks in {@code run()} and, optionally, opens windows. */
    static final class WindowAdapter implements IntegrationAdapter, PairingWindowControl {

        private final IntegrationId integrationId;
        private final Clock clock;
        private final java.util.function.Supplier<RuntimeException> failure;
        private final AtomicReference<String> openedOn = new AtomicReference<>();
        private volatile int openCount;

        WindowAdapter(IntegrationId integrationId, Clock clock,
                      java.util.function.Supplier<RuntimeException> failure) {
            this.integrationId = integrationId;
            this.clock = clock;
            this.failure = failure;
        }

        @Override
        public void initialize() {
        }

        @Override
        public void run() throws Exception {
            new CountDownLatch(1).await();   // a healthy adapter parks until interrupted
        }

        @Override
        public void close() {
        }

        @Override
        public CommandHandler commandHandler() {
            return null;
        }

        @Override
        public PairingWindow openPairingWindow(PairingWindowRequest request) {
            openedOn.set(Thread.currentThread().getName());
            if (failure != null) {
                throw failure.get();
            }
            openCount++;
            Instant opensAt = clock.instant();
            return new PairingWindow(integrationId, opensAt,
                    opensAt.plusSeconds(request.durationSeconds()), request.durationSeconds(),
                    request.reason(), request.actor(), request.scope());
        }

        @Override
        public Optional<PairingWindow> currentPairingWindow() {
            return Optional.empty();
        }

        String openedOn() {
            return openedOn.get();
        }

        int openCount() {
            return openCount;
        }
    }

    /** The factory: {@code withControl} hosts a {@link WindowAdapter}; {@code withoutControl} a plain {@link TestAdapter}. */
    static final class WindowFactory implements IntegrationFactory {

        private final String integrationType;
        private final Clock clock;
        private final boolean control;
        private final java.util.function.Supplier<RuntimeException> failure;
        private final AtomicReference<WindowAdapter> adapter = new AtomicReference<>();

        private WindowFactory(String integrationType, Clock clock, boolean control,
                              java.util.function.Supplier<RuntimeException> failure) {
            this.integrationType = integrationType;
            this.clock = clock;
            this.control = control;
            this.failure = failure;
        }

        static WindowFactory withControl(String integrationType, Clock clock) {
            return new WindowFactory(integrationType, clock, true, null);
        }

        static WindowFactory throwing(String integrationType,
                                      java.util.function.Supplier<RuntimeException> failure) {
            return new WindowFactory(integrationType, Clock.fixed(T0, java.time.ZoneOffset.UTC),
                    true, failure);
        }

        static WindowFactory withoutControl(String integrationType) {
            return new WindowFactory(integrationType, null, false, null);
        }

        @Override
        public IntegrationDescriptor descriptor() {
            return new IntegrationDescriptor(
                    integrationType, "Window " + integrationType, IoType.NETWORK,
                    Set.of(), Set.of(DataPath.DOMAIN), HealthParameters.defaults(), Set.of(),
                    1, 1, 0, Set.of(), BackoffParameters.defaults(),
                    IsolationLevel.IN_JVM, null);
        }

        @Override
        public IntegrationAdapter create(IntegrationContext context)
                throws PermanentIntegrationException {
            if (!control) {
                return TestAdapter.builder().onRun(WindowFactory::blockUntilInterrupted).build();
            }
            WindowAdapter created = new WindowAdapter(context.integrationId(), clock, failure);
            adapter.set(created);
            return created;
        }

        WindowAdapter adapter() {
            return adapter.get();
        }

        private static void blockUntilInterrupted() {
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
