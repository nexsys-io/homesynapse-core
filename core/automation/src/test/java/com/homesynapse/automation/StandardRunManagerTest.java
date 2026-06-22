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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
import com.homesynapse.state.Availability;
import com.homesynapse.state.EntityState;
import com.homesynapse.state.StateQueryService;
import com.homesynapse.state.StateSnapshot;
import com.homesynapse.value.IntValue;

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
    private AutomationTestSupport.StubStateQueryService stateQuery;
    private FakeGate gate;
    private FakeExecutor executor;
    private StandardRunManager manager;

    @BeforeEach
    void setUp() {
        publisher = new AutomationTestSupport.RecordingEventPublisher();
        stateQuery = new AutomationTestSupport.StubStateQueryService(
                AutomationTestSupport.snapshot(Map.of()));
        gate = new FakeGate();
        executor = new FakeExecutor();
        manager = new StandardRunManager(publisher, executor, gate, stateQuery,
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
    @DisplayName("PARALLEL at maxConcurrent drops with reason queue_full (still drops, unlike QUEUED)")
    void parallel_overMaxConcurrent_skipsQueueFull() throws Exception {
        executor.block();
        AutomationDefinition auto = automation(automationId(), ConcurrencyMode.PARALLEL, 1, 0);

        Optional<RunId> first = initiate(auto, triggerEvent(), RunCausalChain.root());
        Optional<RunId> second = initiate(auto, triggerEvent(), RunCausalChain.root());

        assertThat(first).isPresent();
        assertThat(second).isEmpty();
        List<EventEnvelope> skipped = publisher.ofType(EventTypes.AUTOMATION_RUN_SKIPPED);
        assertThat(skipped).hasSize(1);
        assertThat(((AutomationRunSkippedEvent) skipped.get(0).payload()).reason())
                .isEqualTo("queue_full");
    }

    @Test
    @DisplayName("QUEUED (maxConcurrent 1) drains single-flight: queued Runs run one-at-a-time in order")
    void queued_sequentialSingleFlightDrain() throws Exception {
        executor.block();                                    // hold the active Run RUNNING
        AutomationDefinition auto = automation(automationId(), ConcurrencyMode.QUEUED, 1, 0);

        Optional<RunId> r1 = initiate(auto, triggerEvent(), RunCausalChain.root());
        Optional<RunId> r2 = initiate(auto, triggerEvent(), RunCausalChain.root());
        Optional<RunId> r3 = initiate(auto, triggerEvent(), RunCausalChain.root());

        // All three are initiated (QUEUED enqueues rather than dropping) — no queue_full.
        assertThat(r1).isPresent();
        assertThat(r2).isPresent();
        assertThat(r3).isPresent();
        assertThat(publisher.ofType(EventTypes.AUTOMATION_RUN_SKIPPED)).isEmpty();
        // Only one Run is active (single-flight); only its triggered has published.
        assertThat(manager.activeRunCount(auto.automationId())).isEqualTo(1);
        assertThat(publisher.countOfType(EventTypes.AUTOMATION_TRIGGERED)).isEqualTo(1);

        executor.release();                                  // drain: R1 -> R2 -> R3 one-at-a-time
        manager.awaitQuiescence(AWAIT_MS);

        assertThat(publisher.countOfType(EventTypes.AUTOMATION_TRIGGERED)).isEqualTo(3);
        assertThat(publisher.countOfType(EventTypes.AUTOMATION_COMPLETED)).isEqualTo(3);
        // The drain order is the FIFO enqueue order (single-flight sequencing).
        assertThat(executor.executedOrder()).containsExactly(
                r1.orElseThrow().value(), r2.orElseThrow().value(), r3.orElseThrow().value());
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

    // ---- M7.2a-2 DP-A carries ----------------------------------------------

    @Test
    @DisplayName("DP-A.4: RunContext carries the real trigger-time snapshot viewPosition (not 0)")
    void runContext_carriesRealSnapshotPosition() throws Exception {
        stateQuery.setSnapshot(AutomationTestSupport.snapshotAt(42L));
        executor.block();                                    // keep the Run active to read its context
        AutomationDefinition auto = automation(automationId(), ConcurrencyMode.SINGLE, 1, 0);

        Optional<RunId> run = initiate(auto, triggerEvent(), RunCausalChain.root());

        assertThat(run).isPresent();
        RunContext context = manager.getActiveRun(run.orElseThrow()).orElseThrow();
        assertThat(context.stateSnapshotPosition()).isEqualTo(42L);

        executor.release();
        manager.awaitQuiescence(AWAIT_MS);
    }

    @Test
    @DisplayName("DP-A.4: automation_completed carries the executor's real action/command tally")
    void completed_carriesRealActionAndCommandTally() throws Exception {
        executor.tally(2, 3);
        AutomationDefinition auto = automation(automationId(), ConcurrencyMode.SINGLE, 1, 0);

        Optional<RunId> run = initiate(auto, triggerEvent(), RunCausalChain.root());
        manager.awaitQuiescence(AWAIT_MS);

        AutomationCompletedEvent c = onlyCompleted();
        assertThat(c.finalStatus()).isEqualTo("COMPLETED");
        assertThat(c.actionCount()).isEqualTo(2);
        assertThat(c.commandCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("DP-A.3: the dedup claim precedes the gate — a duplicate raced during the gate is deduped")
    void dedupClaimedBeforeGate_reentrantDuplicateIsDeduped() throws Exception {
        AutomationDefinition auto = automation(automationId(), ConcurrencyMode.SINGLE, 1, 0);
        EventEnvelope event = triggerEvent();
        // A gate that, on its first call, races a duplicate initiate of the same (automation,
        // event). Because the dedup key is claimed BEFORE the gate runs, the re-entrant call is
        // deduped immediately (it never reaches the gate again) — proving claim-before-gate.
        AtomicReference<Optional<RunId>> reentrant = new AtomicReference<>();
        RunConditionGate racingGate = (a, ctx, te, snap) -> {
            reentrant.compareAndSet(null,
                    manager.initiateRun(auto, event, TRIGGER_0, NO_TARGETS, RunCausalChain.root()));
            return true;
        };
        manager = new StandardRunManager(publisher, executor, racingGate, stateQuery,
                AutomationTestSupport.FIXED_CLOCK, RunManagerConfig.defaults());

        Optional<RunId> first = manager.initiateRun(auto, event, TRIGGER_0, NO_TARGETS,
                RunCausalChain.root());
        manager.awaitQuiescence(AWAIT_MS);

        assertThat(first).isPresent();
        assertThat(reentrant.get()).isEmpty();               // the raced duplicate was deduped
        assertThat(publisher.countOfType(EventTypes.AUTOMATION_TRIGGERED)).isEqualTo(1);
    }

    // ---- M7.2b computed-param resolution (Doc 16 §3.2) ---------------------

    @Test
    @DisplayName("computed-param: a CommandAction ComputedValue parameter is resolved to a concrete value before the executor runs")
    void computedParam_resolvedToConcreteValueBeforeExecutor() throws Exception {
        AutomationDefinition auto = automationWithCommand(
                Map.of("level", new LiteralValue(new IntValue(75))));

        Optional<RunId> run = initiate(auto, triggerEvent(), RunCausalChain.root());
        manager.awaitQuiescence(AWAIT_MS);

        assertThat(run).isPresent();
        CommandAction executed = onlyCommandAction(executor.lastActions);
        assertThat(executed.parameters().get("level")).isEqualTo(new IntValue(75));
        assertThat(executed.parameters().get("level")).isNotInstanceOf(ComputedValue.class);
    }

    @Test
    @DisplayName("computed-param: an AttributeRef resolves against the captured trigger-time snapshot (single read, AMD-03)")
    void computedParam_resolvesAgainstCapturedSnapshot() throws Exception {
        EntityState lamp = AutomationTestSupport.state(entity, Availability.AVAILABLE,
                Map.of("level", new IntValue(55)));
        CountingStateQuery counting = new CountingStateQuery(
                AutomationTestSupport.snapshot(Map.of(entity, lamp)));
        manager = new StandardRunManager(publisher, executor, gate, counting,
                AutomationTestSupport.FIXED_CLOCK, RunManagerConfig.defaults());
        AutomationDefinition auto = automationWithCommand(
                Map.of("level", new AttributeRef(entity, "level")));

        Optional<RunId> run = initiate(auto, triggerEvent(), RunCausalChain.root());
        manager.awaitQuiescence(AWAIT_MS);

        assertThat(run).isPresent();
        CommandAction executed = onlyCommandAction(executor.lastActions);
        assertThat(executed.parameters().get("level")).isEqualTo(new IntValue(55));
        assertThat(counting.reads.get()).isEqualTo(1);   // one captured snapshot drove resolution + gate
    }

    // ---- M7.2b terminal contract: fail-closed read + no retry --------------

    @Test
    @DisplayName("fail-closed read: a degraded trigger-time snapshot read → FAILED with the degraded-read reason, no execution, no retry")
    void failClosedRead_snapshotReadThrows_runFailedWithReason() throws Exception {
        StandardRunManager failing = new StandardRunManager(publisher, executor, gate,
                new ThrowingStateQuery(), AutomationTestSupport.FIXED_CLOCK,
                RunManagerConfig.defaults());
        AutomationDefinition auto = automation(automationId(), ConcurrencyMode.SINGLE, 1, 0);
        EventEnvelope event = triggerEvent();

        Optional<RunId> first = failing.initiateRun(auto, event, TRIGGER_0, NO_TARGETS,
                RunCausalChain.root());
        Optional<RunId> retry = failing.initiateRun(auto, event, TRIGGER_0, NO_TARGETS,
                RunCausalChain.root());

        assertThat(first).isPresent();
        assertThat(failing.getStatus(first.orElseThrow())).isEqualTo(RunStatus.FAILED);
        assertThat(executor.invocations.get()).isZero();          // never proceeded to action execution
        AutomationCompletedEvent c = onlyCompleted();
        assertThat(c.finalStatus()).isEqualTo("FAILED");
        assertThat(c.failureReason()).contains("snapshot read failed closed");
        assertThat(c.abortReason()).isNull();
        // C1 pair preserved; the same degraded trigger is deduped (no autonomous retry — AMD-90).
        assertThat(retry).isEmpty();
        assertThat(publisher.countOfType(EventTypes.AUTOMATION_TRIGGERED)).isEqualTo(1);
        assertThat(publisher.countOfType(EventTypes.AUTOMATION_COMPLETED)).isEqualTo(1);
    }

    @Test
    @DisplayName("AMD-90: a failing action is executed once and never autonomously re-dispatched")
    void failingAction_noAutonomousRetry() throws Exception {
        executor.throwWith("device unreachable");
        AutomationDefinition auto = automation(automationId(), ConcurrencyMode.SINGLE, 1, 0);

        Optional<RunId> run = initiate(auto, triggerEvent(), RunCausalChain.root());
        manager.awaitQuiescence(AWAIT_MS);

        assertThat(manager.getStatus(run.orElseThrow())).isEqualTo(RunStatus.FAILED);
        assertThat(executor.invocations.get()).isEqualTo(1);      // one dispatch, never retried
        assertThat(publisher.countOfType(EventTypes.AUTOMATION_TRIGGERED)).isEqualTo(1);
        assertThat(publisher.countOfType(EventTypes.AUTOMATION_COMPLETED)).isEqualTo(1);
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

    /** A SINGLE-mode automation whose single action is a {@link CommandAction} with the given parameters. */
    private AutomationDefinition automationWithCommand(Map<String, Object> parameters) {
        CommandAction command = new CommandAction(new DirectRefSelector(entity), "set_level",
                parameters, UnavailablePolicy.SKIP);
        return new AutomationDefinition(automationId(), "auto", "auto", null, true,
                ConcurrencyMode.SINGLE, 1, MaxExceededSeverity.INFO, 0,
                List.of(new StateTrigger(new DirectRefSelector(entity), "on_off", "on", null, "t1")),
                List.of(), List.of(command));
    }

    private static CommandAction onlyCommandAction(List<ActionDefinition> actions) {
        assertThat(actions).hasSize(1);
        assertThat(actions.get(0)).isInstanceOf(CommandAction.class);
        return (CommandAction) actions.get(0);
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

    /** A configurable {@link RunConditionGate}; returns {@link #result}, ignoring the snapshot. */
    private static final class FakeGate implements RunConditionGate {
        private volatile boolean result = true;
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public boolean conditionsHold(AutomationDefinition automation, RunContext context,
                                      EventEnvelope triggeringEvent,
                                      com.homesynapse.state.StateSnapshot snapshot) {
            calls.incrementAndGet();
            return result;
        }
    }

    /**
     * A configurable {@link ActionExecutor}: completes (with a configurable tally), throws, or
     * blocks on a latch until released or interrupted (restoring the interrupt flag so the FSM
     * finalizes ABORTED). Records the order in which Runs execute (for QUEUED sequencing).
     */
    private static final class FakeExecutor implements ActionExecutor {

        private enum Mode { COMPLETE, THROW, BLOCK }

        private final AtomicInteger invocations = new AtomicInteger();
        private final List<Ulid> executedOrder = new CopyOnWriteArrayList<>();
        private volatile List<ActionDefinition> lastActions = List.of();
        private volatile Mode mode = Mode.COMPLETE;
        private volatile String error = "action failed";
        private volatile int actionCount = 0;
        private volatile int commandCount = 0;
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

        void tally(int actions, int commands) {
            this.actionCount = actions;
            this.commandCount = commands;
        }

        List<Ulid> executedOrder() {
            return List.copyOf(executedOrder);
        }

        @Override
        public ActionExecutionResult execute(List<ActionDefinition> actions, RunContext context,
                                             EventEnvelope triggeringEvent) {
            invocations.incrementAndGet();
            lastActions = actions;                        // capture for computed-param resolution assertions
            executedOrder.add(context.runId().value());
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
            return ActionExecutionResult.succeeded(actionCount, commandCount);
        }
    }

    /** A {@link StateQueryService} returning a fixed snapshot and counting {@code getSnapshot} reads. */
    private static final class CountingStateQuery implements StateQueryService {
        private final StateSnapshot snapshot;
        private final AtomicInteger reads = new AtomicInteger();

        CountingStateQuery(StateSnapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override
        public Optional<EntityState> getState(EntityId entityId) {
            return Optional.ofNullable(snapshot.states().get(entityId));
        }

        @Override
        public Map<EntityId, EntityState> getStates(Set<EntityId> entityIds) {
            return Map.of();
        }

        @Override
        public StateSnapshot getSnapshot() {
            reads.incrementAndGet();
            return snapshot;
        }

        @Override
        public long getViewPosition() {
            return snapshot.viewPosition();
        }

        @Override
        public boolean isReady() {
            return true;
        }
    }

    /** A {@link StateQueryService} whose snapshot read fails closed (the post-AB-2 degraded read). */
    private static final class ThrowingStateQuery implements StateQueryService {
        @Override
        public Optional<EntityState> getState(EntityId entityId) {
            return Optional.empty();
        }

        @Override
        public Map<EntityId, EntityState> getStates(Set<EntityId> entityIds) {
            return Map.of();
        }

        @Override
        public StateSnapshot getSnapshot() {
            throw new IllegalStateException("payload decrypt failed (fail-closed)");
        }

        @Override
        public long getViewPosition() {
            return 0L;
        }

        @Override
        public boolean isReady() {
            return false;
        }
    }
}
