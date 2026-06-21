/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import com.homesynapse.automation.RunCausalChain.ChainLink;
import com.homesynapse.event.AutomationCompletedEvent;
import com.homesynapse.event.AutomationDisabledEvent;
import com.homesynapse.event.AutomationRunCancelledEvent;
import com.homesynapse.event.AutomationRunSkippedEvent;
import com.homesynapse.event.CascadeDepthExceededEvent;
import com.homesynapse.event.CascadeLoopDetectedEvent;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventTypes;
import com.homesynapse.platform.identity.AutomationId;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.Ulid;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The {@link StandardRunManager} FSM contract suite — built against a fake
 * {@link ActionExecutor} + {@link RunConditionGate}, the recording publisher, and a fixed
 * clock (§4c). Covers dedup, the C3 comparator, the four concurrency modes, the
 * condition-before-mode / no-slot contract, terminal transitions, cascade depth + cycle
 * suppression and its determinism, auto-disable, REPLAY zombie finalization, and payload
 * residency.
 */
@DisplayName("StandardRunManager (M7.2a-1 run lifecycle)")
class StandardRunManagerTest {

    private static final long AWAIT_MS = 5_000L;
    private static final Map<String, Set<EntityId>> NO_TARGETS = Map.of();
    private static final List<Integer> TRIGGER_0 = List.of(0);

    private final EntityId entity = AutomationTestSupport.entityId();

    private AutomationTestSupport.RecordingEventPublisher publisher;
    private FakeGate gate;
    private FakeExecutor executor;
    private StandardRunManager manager;

    @BeforeEach
    void setUp() {
        publisher = new AutomationTestSupport.RecordingEventPublisher();
        gate = new FakeGate();
        executor = new FakeExecutor();
        manager = new StandardRunManager(publisher, executor, gate,
                AutomationTestSupport.FIXED_CLOCK, RunManagerConfig.defaults());
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        manager.close();                 // interrupt any blocked Run VT (shutdown → ABORTED)
        manager.awaitQuiescence(AWAIT_MS);
    }

    // ---- Dedup (C2) ---------------------------------------------------------

    @Test
    @DisplayName("dedup: the same (automation, event) admits one Run; the second is empty")
    void dedup_sameAutomationSameEvent_secondReturnsEmpty() throws Exception {
        AutomationDefinition auto = automation(automationId(), ConcurrencyMode.SINGLE, 1, 0);
        EventEnvelope event = triggerEvent();

        Optional<RunId> first = initiate(auto, event, RunCausalChain.root());
        Optional<RunId> second = initiate(auto, event, RunCausalChain.root());

        assertThat(first).isPresent();
        assertThat(second).isEmpty();
        manager.awaitQuiescence(AWAIT_MS);
        assertThat(publisher.countOfType(EventTypes.AUTOMATION_TRIGGERED)).isEqualTo(1);
    }

    // ---- C3 execution order -------------------------------------------------

    @Test
    @DisplayName("byExecutionOrder orders priority descending, then automationId ascending")
    void byExecutionOrder_priorityDescThenIdAsc() {
        AutomationId lowId = AutomationId.of(Ulid.parse("00000000000000000000000000"));
        AutomationId highId = AutomationId.of(Ulid.parse("00000000000000000000000001"));
        AutomationId midPriorityId = AutomationId.of(Ulid.parse("00000000000000000000000002"));
        AutomationDefinition p10low = automation(lowId, ConcurrencyMode.SINGLE, 1, 10);
        AutomationDefinition p10high = automation(highId, ConcurrencyMode.SINGLE, 1, 10);
        AutomationDefinition p5 = automation(midPriorityId, ConcurrencyMode.SINGLE, 1, 5);

        List<AutomationDefinition> sorted = Stream.of(p5, p10high, p10low)
                .sorted(StandardRunManager.byExecutionOrder())
                .toList();

        assertThat(sorted).containsExactly(p10low, p10high, p5);
    }

    // ---- Concurrency modes --------------------------------------------------

    @Test
    @DisplayName("SINGLE drops a concurrent trigger with reason mode_busy")
    void single_dropsConcurrentTrigger() throws Exception {
        executor.block();
        AutomationDefinition auto = automation(automationId(), ConcurrencyMode.SINGLE, 1, 0);

        Optional<RunId> first = initiate(auto, triggerEvent(), RunCausalChain.root());
        Optional<RunId> second = initiate(auto, triggerEvent(), RunCausalChain.root());

        assertThat(first).isPresent();
        assertThat(second).isEmpty();
        List<EventEnvelope> skipped = publisher.ofType(EventTypes.AUTOMATION_RUN_SKIPPED);
        assertThat(skipped).hasSize(1);
        AutomationRunSkippedEvent payload = (AutomationRunSkippedEvent) skipped.get(0).payload();
        assertThat(payload.reason()).isEqualTo("mode_busy");
        assertThat(payload.mode()).isEqualTo("SINGLE");
        assertThat(payload.activeRunId()).isEqualTo(first.orElseThrow().value());
        assertThat(publisher.countOfType(EventTypes.AUTOMATION_TRIGGERED)).isEqualTo(1);
    }

    @Test
    @DisplayName("RESTART cancels the active Run (ABORTED + cancelled), then admits the new Run")
    void restart_cancelsActive_thenStartsNew() throws Exception {
        executor.block();
        AutomationDefinition auto = automation(automationId(), ConcurrencyMode.RESTART, 1, 0);
        EventEnvelope ev1 = triggerEvent();
        EventEnvelope ev2 = triggerEvent();

        Optional<RunId> first = initiate(auto, ev1, RunCausalChain.root());
        Optional<RunId> second = initiate(auto, ev2, RunCausalChain.root());

        assertThat(first).isPresent();
        assertThat(second).isPresent();
        assertThat(second).isNotEqualTo(first);

        List<EventEnvelope> cancelled = publisher.ofType(EventTypes.AUTOMATION_RUN_CANCELLED);
        assertThat(cancelled).hasSize(1);
        AutomationRunCancelledEvent c = (AutomationRunCancelledEvent) cancelled.get(0).payload();
        assertThat(c.cancelledRunId()).isEqualTo(first.orElseThrow().value());
        assertThat(c.replacingEventId()).isEqualTo(ev2.eventId());
        assertThat(c.triggeringEventId()).isEqualTo(ev1.eventId());

        executor.release();                                  // let the surviving Run complete
        manager.awaitQuiescence(AWAIT_MS);

        Map<Ulid, String> finals = finalStatusesByRun();
        assertThat(finals.get(first.orElseThrow().value())).isEqualTo("ABORTED");
        assertThat(finals.get(second.orElseThrow().value())).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("QUEUED at maxConcurrent drops with reason queue_full")
    void queued_overMaxConcurrent_skipsQueueFull() throws Exception {
        executor.block();
        AutomationDefinition auto = automation(automationId(), ConcurrencyMode.QUEUED, 1, 0);

        Optional<RunId> first = initiate(auto, triggerEvent(), RunCausalChain.root());
        Optional<RunId> second = initiate(auto, triggerEvent(), RunCausalChain.root());

        assertThat(first).isPresent();
        assertThat(second).isEmpty();
        List<EventEnvelope> skipped = publisher.ofType(EventTypes.AUTOMATION_RUN_SKIPPED);
        assertThat(skipped).hasSize(1);
        assertThat(((AutomationRunSkippedEvent) skipped.get(0).payload()).reason())
                .isEqualTo("queue_full");
    }

    // ---- EVALUATING / CONDITION_NOT_MET ------------------------------------

    @Test
    @DisplayName("conditions false → CONDITION_NOT_MET, no mode slot consumed; next Run admitted")
    void conditionsFalse_terminatesConditionNotMet_noModeSlotConsumed() throws Exception {
        AutomationDefinition auto = automation(automationId(), ConcurrencyMode.SINGLE, 1, 0);

        gate.result = false;
        Optional<RunId> first = initiate(auto, triggerEvent(), RunCausalChain.root());

        assertThat(first).isPresent();
        assertThat(manager.getStatus(first.orElseThrow())).isEqualTo(RunStatus.CONDITION_NOT_MET);
        assertThat(manager.activeRunCount(auto.automationId())).isZero();   // slot not consumed
        assertThat(executor.invocations.get()).isZero();                   // actions never ran

        List<EventEnvelope> completed = publisher.ofType(EventTypes.AUTOMATION_COMPLETED);
        assertThat(completed).hasSize(1);
        assertThat(((AutomationCompletedEvent) completed.get(0).payload()).finalStatus())
                .isEqualTo("CONDITION_NOT_MET");
        assertThat(publisher.countOfType(EventTypes.AUTOMATION_TRIGGERED)).isEqualTo(1);

        // A subsequent SINGLE Run is admitted — the slot was never held.
        gate.result = true;
        Optional<RunId> second = initiate(auto, triggerEvent(), RunCausalChain.root());
        assertThat(second).isPresent();
    }

    // ---- Terminal transitions ----------------------------------------------

    @Test
    @DisplayName("actions complete → COMPLETED + automation_completed(COMPLETED)")
    void actionsComplete_runCompleted() throws Exception {
        AutomationDefinition auto = automation(automationId(), ConcurrencyMode.SINGLE, 1, 0);

        Optional<RunId> run = initiate(auto, triggerEvent(), RunCausalChain.root());
        manager.awaitQuiescence(AWAIT_MS);

        assertThat(manager.getStatus(run.orElseThrow())).isEqualTo(RunStatus.COMPLETED);
        assertThat(executor.invocations.get()).isEqualTo(1);
        AutomationCompletedEvent c = onlyCompleted();
        assertThat(c.finalStatus()).isEqualTo("COMPLETED");
        assertThat(c.runId()).isEqualTo(run.orElseThrow().value());
        assertThat(c.failureReason()).isNull();
    }

    @Test
    @DisplayName("an action throwing → FAILED + automation_completed(FAILED) with the reason")
    void actionThrows_runFailed() throws Exception {
        executor.throwWith("device unreachable");
        AutomationDefinition auto = automation(automationId(), ConcurrencyMode.SINGLE, 1, 0);

        Optional<RunId> run = initiate(auto, triggerEvent(), RunCausalChain.root());
        manager.awaitQuiescence(AWAIT_MS);

        assertThat(manager.getStatus(run.orElseThrow())).isEqualTo(RunStatus.FAILED);
        AutomationCompletedEvent c = onlyCompleted();
        assertThat(c.finalStatus()).isEqualTo("FAILED");
        assertThat(c.failureReason()).isEqualTo("device unreachable");
    }

    // ---- Cascade governance -------------------------------------------------

    @Test
    @DisplayName("depth ceiling reached → suppressed + cascade_depth_exceeded; config range pins")
    void cascadeDepthExceeded_suppressedAndDiagnostic() {
        AutomationDefinition auto = automation(automationId(), ConcurrencyMode.SINGLE, 1, 0);
        RunCausalChain atCeiling = chainOfDepth(RunManagerConfig.DEFAULT_MAX_CASCADE_DEPTH);

        Optional<RunId> result = initiate(auto, triggerEvent(), atCeiling);

        assertThat(result).isEmpty();
        List<EventEnvelope> diag = publisher.ofType(EventTypes.CASCADE_DEPTH_EXCEEDED);
        assertThat(diag).hasSize(1);
        CascadeDepthExceededEvent d = (CascadeDepthExceededEvent) diag.get(0).payload();
        assertThat(d.cascadeDepth()).isEqualTo(8);
        assertThat(d.maxCascadeDepth()).isEqualTo(8);
        assertThat(publisher.countOfType(EventTypes.AUTOMATION_TRIGGERED)).isZero();

        // Config range pins: default 8; valid bounds 1..32; out-of-range rejected.
        assertThat(RunManagerConfig.defaults().maxCascadeDepth()).isEqualTo(8);
        assertThat(new RunManagerConfig(1, 5, Duration.ofMinutes(10)).maxCascadeDepth()).isEqualTo(1);
        assertThat(new RunManagerConfig(32, 5, Duration.ofMinutes(10)).maxCascadeDepth()).isEqualTo(32);
        assertThatThrownBy(() -> new RunManagerConfig(0, 5, Duration.ofMinutes(10)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RunManagerConfig(33, 5, Duration.ofMinutes(10)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a cycle → suppressed + cascade_loop_detected (distinct); a distinct chain proceeds")
    void cascadeCycle_suppressedAndDistinctDiagnostic() throws Exception {
        AutomationId idA = automationId();
        AutomationId idB = automationId();
        RunId runA = new RunId(AutomationTestSupport.ulid());
        AutomationDefinition autoA = automation(idA, ConcurrencyMode.SINGLE, 1, 0);
        RunCausalChain cycleChain = RunCausalChain.root()
                .extend(new ChainLink(runA, idA))
                .extend(new ChainLink(new RunId(AutomationTestSupport.ulid()), idB));

        Optional<RunId> suppressed = initiate(autoA, triggerEvent(), cycleChain);

        assertThat(suppressed).isEmpty();
        List<EventEnvelope> loops = publisher.ofType(EventTypes.CASCADE_LOOP_DETECTED);
        assertThat(loops).hasSize(1);
        CascadeLoopDetectedEvent d = (CascadeLoopDetectedEvent) loops.get(0).payload();
        assertThat(d.chain()).containsExactly(idA, idB, idA);          // ancestors + this id
        assertThat(d.originalRunId()).isEqualTo(runA.value());
        assertThat(publisher.ofType(EventTypes.CASCADE_DEPTH_EXCEEDED)).isEmpty();  // distinct diagnostic

        // A -> B -> C (all distinct) proceeds — the chain does not contain C.
        AutomationId idC = automationId();
        AutomationDefinition autoC = automation(idC, ConcurrencyMode.SINGLE, 1, 0);
        Optional<RunId> proceeds = initiate(autoC, triggerEvent(), cycleChain);
        assertThat(proceeds).isPresent();
        manager.awaitQuiescence(AWAIT_MS);
    }

    @Test
    @DisplayName("cascade suppression is deterministic — identical chain + config, identical result")
    void cascadeSuppression_isDeterministic() {
        AutomationId idA = automationId();
        AutomationId idB = automationId();
        AutomationDefinition autoA = automation(idA, ConcurrencyMode.SINGLE, 1, 0);
        RunCausalChain cycleChain = RunCausalChain.root()
                .extend(new ChainLink(new RunId(AutomationTestSupport.ulid()), idA))
                .extend(new ChainLink(new RunId(AutomationTestSupport.ulid()), idB));

        Optional<RunId> r1 = initiate(autoA, triggerEvent(), cycleChain);
        Optional<RunId> r2 = initiate(autoA, triggerEvent(), cycleChain);

        assertThat(r1).isEmpty();
        assertThat(r2).isEmpty();
        List<EventEnvelope> loops = publisher.ofType(EventTypes.CASCADE_LOOP_DETECTED);
        assertThat(loops).hasSize(2);
        CascadeLoopDetectedEvent first = (CascadeLoopDetectedEvent) loops.get(0).payload();
        CascadeLoopDetectedEvent second = (CascadeLoopDetectedEvent) loops.get(1).payload();
        assertThat(second.chain()).isEqualTo(first.chain());          // no window sensitivity
    }

    // ---- Auto-disable -------------------------------------------------------

    @Test
    @DisplayName("threshold failures within the window → automation_disabled; later triggers suppressed")
    void autoDisable_afterThresholdFailures_disablesAndEmits() throws Exception {
        executor.throwWith("boom");
        AutomationDefinition auto = automation(automationId(), ConcurrencyMode.SINGLE, 1, 0);

        for (int i = 0; i < RunManagerConfig.DEFAULT_AUTO_DISABLE_THRESHOLD; i++) {
            Optional<RunId> run = initiate(auto, triggerEvent(), RunCausalChain.root());
            assertThat(run).isPresent();
            manager.awaitQuiescence(AWAIT_MS);
        }

        List<EventEnvelope> disabled = publisher.ofType(EventTypes.AUTOMATION_DISABLED);
        assertThat(disabled).hasSize(1);
        AutomationDisabledEvent d = (AutomationDisabledEvent) disabled.get(0).payload();
        assertThat(d.failureCount()).isEqualTo(5);
        assertThat(d.windowMinutes()).isEqualTo(10);
        assertThat(d.lastError()).isEqualTo("boom");

        // Subsequent triggers are suppressed — no new triggered event.
        Optional<RunId> afterDisable = initiate(auto, triggerEvent(), RunCausalChain.root());
        assertThat(afterDisable).isEmpty();
        assertThat(publisher.countOfType(EventTypes.AUTOMATION_TRIGGERED)).isEqualTo(5);
    }

    // ---- REPLAY zombie finalization ----------------------------------------

    @Test
    @DisplayName("a triggered-never-completed zombie → INTERRUPTED completion, slot freed, not re-executed")
    void replayZombie_triggeredNeverCompleted_finalizesInterrupted() throws Exception {
        AutomationId id = automationId();
        Ulid zombieRunId = AutomationTestSupport.ulid();
        RunManager.ZombieRun zombie = new RunManager.ZombieRun(
                zombieRunId, id, AutomationTestSupport.ulid(), AutomationTestSupport.ulid(),
                id.value(), null);

        int finalized = manager.finalizeZombieRuns(List.of(zombie));

        assertThat(finalized).isEqualTo(1);
        AutomationCompletedEvent c = onlyCompleted();
        assertThat(c.runId()).isEqualTo(zombieRunId);
        assertThat(c.finalStatus()).isEqualTo("INTERRUPTED");
        assertThat(c.abortReason()).isEqualTo("interrupted_by_crash");
        assertThat(executor.invocations.get()).isZero();                 // re-derived, not re-executed
        assertThat(manager.getStatus(new RunId(zombieRunId))).isEqualTo(RunStatus.INTERRUPTED);

        // The mode slot is free — a fresh SINGLE Run for the same automation is admitted.
        AutomationDefinition auto = automation(id, ConcurrencyMode.SINGLE, 1, 0);
        Optional<RunId> fresh = initiate(auto, triggerEvent(), RunCausalChain.root());
        assertThat(fresh).isPresent();
        manager.awaitQuiescence(AWAIT_MS);
    }

    // ---- Residency ----------------------------------------------------------

    @Test
    @DisplayName("no published payload references an automation-resident type (everything flattened)")
    void causalChain_neverInPayload() throws Exception {
        AutomationDefinition auto = automation(automationId(), ConcurrencyMode.SINGLE, 1, 0);
        // A normal Run (triggered + completed) plus a suppressed cascade diagnostic.
        initiate(auto, triggerEvent(), RunCausalChain.root());
        initiate(automation(automationId(), ConcurrencyMode.SINGLE, 1, 0), triggerEvent(),
                chainOfDepth(RunManagerConfig.DEFAULT_MAX_CASCADE_DEPTH));
        manager.awaitQuiescence(AWAIT_MS);

        assertThat(publisher.published()).isNotEmpty();
        for (EventEnvelope envelope : publisher.published()) {
            for (RecordComponent rc : envelope.payload().getClass().getRecordComponents()) {
                assertThat(rc.getType().getPackageName())
                        .as("payload %s component %s",
                                envelope.payload().getClass().getSimpleName(), rc.getName())
                        .doesNotStartWith("com.homesynapse.automation")
                        .matches("java\\..*|com\\.homesynapse\\.platform.*"
                                + "|com\\.homesynapse\\.event|com\\.homesynapse\\.value");
            }
        }
    }

    // ---- Helpers ------------------------------------------------------------

    private Optional<RunId> initiate(AutomationDefinition auto, EventEnvelope event,
                                     RunCausalChain parentChain) {
        return manager.initiateRun(auto, event, TRIGGER_0, NO_TARGETS, parentChain);
    }

    private AutomationDefinition automation(AutomationId id, ConcurrencyMode mode,
                                            int maxConcurrent, int priority) {
        return new AutomationDefinition(id, "auto", "auto", null, true, mode, maxConcurrent,
                MaxExceededSeverity.INFO, priority,
                List.of(new StateTrigger(new DirectRefSelector(entity), "on_off", "on", null, "t1")),
                List.of(), List.of());
    }

    private EventEnvelope triggerEvent() {
        return AutomationTestSupport.stateChanged(entity, "on_off",
                AutomationTestSupport.str("off"), AutomationTestSupport.str("on"));
    }

    private static AutomationId automationId() {
        return AutomationTestSupport.automationId();
    }

    private RunCausalChain chainOfDepth(int depth) {
        RunCausalChain chain = RunCausalChain.root();
        for (int i = 0; i < depth; i++) {
            chain = chain.extend(new ChainLink(new RunId(AutomationTestSupport.ulid()),
                    AutomationTestSupport.automationId()));
        }
        return chain;
    }

    private AutomationCompletedEvent onlyCompleted() {
        List<EventEnvelope> completed = publisher.ofType(EventTypes.AUTOMATION_COMPLETED);
        assertThat(completed).hasSize(1);
        return (AutomationCompletedEvent) completed.get(0).payload();
    }

    private Map<Ulid, String> finalStatusesByRun() {
        Map<Ulid, String> finals = new HashMap<>();
        for (EventEnvelope envelope : publisher.ofType(EventTypes.AUTOMATION_COMPLETED)) {
            AutomationCompletedEvent c = (AutomationCompletedEvent) envelope.payload();
            finals.put(c.runId(), c.finalStatus());
        }
        return finals;
    }

    // ---- Fakes --------------------------------------------------------------

    /** A configurable {@link RunConditionGate}; returns {@link #result}. */
    private static final class FakeGate implements RunConditionGate {
        private volatile boolean result = true;
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public boolean conditionsHold(AutomationDefinition automation, RunContext context) {
            calls.incrementAndGet();
            return result;
        }
    }

    /**
     * A configurable {@link ActionExecutor}: completes immediately, throws, or blocks on a
     * latch until released or interrupted (restoring the interrupt flag so the FSM
     * finalizes ABORTED).
     */
    private static final class FakeExecutor implements ActionExecutor {

        private enum Mode { COMPLETE, THROW, BLOCK }

        private final AtomicInteger invocations = new AtomicInteger();
        private volatile Mode mode = Mode.COMPLETE;
        private volatile String error = "action failed";
        private volatile CountDownLatch latch = new CountDownLatch(0);

        void block() {
            this.latch = new CountDownLatch(1);
            this.mode = Mode.BLOCK;
        }

        void release() {
            this.latch.countDown();
        }

        void throwWith(String message) {
            this.error = message;
            this.mode = Mode.THROW;
        }

        @Override
        public void execute(List<ActionDefinition> actions, RunContext context) {
            invocations.incrementAndGet();
            switch (mode) {
                case COMPLETE -> { /* return immediately */ }
                case THROW -> throw new IllegalStateException(error);
                case BLOCK -> {
                    try {
                        latch.await();
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();   // restore — FSM finalizes ABORTED
                    }
                }
            }
        }
    }
}
