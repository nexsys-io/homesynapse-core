/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import com.homesynapse.event.AutomationCompletedEvent;
import com.homesynapse.event.AutomationDisabledEvent;
import com.homesynapse.event.AutomationRunCancelledEvent;
import com.homesynapse.event.AutomationRunSkippedEvent;
import com.homesynapse.event.AutomationTriggeredEvent;
import com.homesynapse.event.CascadeDepthExceededEvent;
import com.homesynapse.event.CascadeLoopDetectedEvent;
import com.homesynapse.event.CausalContext;
import com.homesynapse.event.DomainEvent;
import com.homesynapse.event.EventDraft;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventId;
import com.homesynapse.event.EventOrigin;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventPublisher;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.SequenceConflictException;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.platform.identity.AutomationId;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.Ulid;
import com.homesynapse.platform.identity.UlidFactory;
import com.homesynapse.state.StateQueryService;
import com.homesynapse.state.StateSnapshot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Production {@link RunManager} FSM: drives a matched automation and its triggering event
 * through {@code EVALUATING -> RUNNING -> terminal}, enforces deduplication (C2), cascade
 * governance (§3.7.1 / AMD-91), concurrency modes (§3.6), and auto-disable (§6.2), and
 * publishes the run-lifecycle events that make a Run observable (Doc 07 §3.7).
 *
 * <p>Package-private; exposed to the composition root only through
 * {@link RunManagerAssembly}. Drives an injected {@link ActionExecutor} (M7.2a-2
 * {@link StandardActionExecutor}) and an injected {@link RunConditionGate} (M7.2a-2
 * {@link StandardRunConditionGate}, which publishes the {@code automation_condition_evaluated}
 * row-4 diagnostic). A {@link StateQueryService} supplies the trigger-time snapshot.</p>
 *
 * <h2>Admission order (Doc 07 §3.4, §3.6; the cross-module condition-before-mode contract)</h2>
 *
 * <ol>
 *   <li><b>Dedup (C2)</b> — key {@code (automationId, triggeringEventId)}; a repeat returns
 *       empty with no event.</li>
 *   <li><b>Cascade cycle / depth</b> — chain membership / depth (AMD-91) suppress with a
 *       {@code cascade_loop_detected} / {@code cascade_depth_exceeded} diagnostic.</li>
 *   <li><b>Auto-disable</b> — a disabled automation is suppressed silently.</li>
 *   <li><b>Snapshot capture (DP-A.4)</b> — the trigger-time {@link StateSnapshot} is captured
 *       once; its {@code viewPosition} fills {@link RunContext#stateSnapshotPosition()} and the
 *       same snapshot drives condition evaluation (AMD-03).</li>
 *   <li><b>Dedup claim (DP-A.3)</b> — the C2 key is claimed <em>before</em> the gate, so a
 *       raced duplicate cannot double-publish the row-4 diagnostic.</li>
 *   <li><b>EVALUATING</b> — the condition gate runs <em>before</em> mode enforcement; a Run
 *       whose conditions are false terminates {@code CONDITION_NOT_MET} immediately and
 *       <em>never consumes a mode slot</em>.</li>
 *   <li><b>Concurrency-mode admission (§3.6)</b> — admit; or for {@code SINGLE}/{@code PARALLEL}
 *       at capacity drop ({@code automation_run_skipped}); or for {@code RESTART} cancel the
 *       active Run ({@code automation_run_cancelled}); or for {@code QUEUED} at capacity
 *       <b>enqueue</b> (DP-A.1) for single-flight sequential draining.</li>
 *   <li><b>Admit</b> — register active, publish {@code automation_triggered}, spawn the Run's
 *       virtual thread.</li>
 * </ol>
 *
 * <h2>QUEUED sequential drain (DP-A.1)</h2>
 *
 * <p>{@code QUEUED} no longer ships as {@code PARALLEL}: at {@code maxConcurrent} a Run
 * enqueues (per-automation FIFO) instead of dropping, and as each active Run terminates a
 * single queued Run is drained and admitted (one-at-a-time as slots free). With
 * {@code maxConcurrent == 1} this is strict single-flight sequencing. Conditions are evaluated
 * at trigger time (AMD-03) before enqueue, so a queued Run is already condition-passed; only
 * {@code automation_triggered} and execution are deferred to drain time.</p>
 *
 * <p><strong>Concurrency (LTD-11 / LTD-01).</strong> Compound admission/terminal/drain updates
 * are serialized by a {@link ReentrantLock} — never {@code synchronized}. Event publication
 * always happens <em>outside</em> the lock. Each Run executes on its own virtual thread.</p>
 *
 * <p><strong>Time (§4c).</strong> All timing derives from the injected {@link Clock}; new
 * {@link RunId}s are generated via {@link UlidFactory#generate(Clock)}.</p>
 */
final class StandardRunManager implements RunManager, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(StandardRunManager.class);

    /** Payload schema version for every run-lifecycle event this FSM publishes. */
    private static final int SCHEMA_VERSION = 1;

    /** Auto-disable reason carried in {@code automation_disabled} (Doc 07 §3.7 table). */
    private static final String DISABLE_REASON = "repeated_failure";

    private final EventPublisher publisher;
    private final ActionExecutor actionExecutor;
    private final RunConditionGate conditionGate;
    private final StateQueryService stateQuery;
    private final Clock clock;
    private final RunManagerConfig config;

    /**
     * Serializes compound admission/terminal/drain state mutations (active-run set, dedup
     * claim, pending queues, auto-disable window). Never {@code synchronized} (LTD-11).
     */
    private final ReentrantLock stateLock = new ReentrantLock();

    /** Active Runs (admitted, RUNNING, not yet terminal), keyed by {@link RunId}. */
    private final Map<RunId, ActiveRun> activeRuns = new ConcurrentHashMap<>();

    /** Run status including terminal states; retained for status queries (no eviction here). */
    private final Map<RunId, RunStatus> statuses = new ConcurrentHashMap<>();

    /** C2 dedup: {@code (automationId, triggeringEventId)} pairs that became Runs. */
    private final Set<DedupKey> dedup = ConcurrentHashMap.newKeySet();

    /** Per-automation FIFO of condition-passed Runs awaiting a free slot (QUEUED); guarded by {@link #stateLock}. */
    private final Map<AutomationId, Deque<ActiveRun>> pendingQueues = new HashMap<>();

    /** Per-automation failure timestamps within the auto-disable window; guarded by {@link #stateLock}. */
    private final Map<AutomationId, Deque<Instant>> failureWindows = new HashMap<>();

    /** Automations currently auto-disabled (triggers suppressed until operator re-enable). */
    private final Set<AutomationId> disabled = ConcurrentHashMap.newKeySet();

    /**
     * Every live Run VT (including {@code RESTART} victims removed from {@link #activeRuns} but
     * still finalizing). Used by {@link #close()} and {@link #awaitQuiescence(long)}.
     */
    private final Set<Thread> liveRunThreads = ConcurrentHashMap.newKeySet();

    /**
     * Constructs the FSM against its injected seams.
     *
     * @param publisher      the durable event publish surface, never {@code null}
     * @param actionExecutor the RUNNING-state executor, never {@code null}
     * @param conditionGate  the EVALUATING-state gate, never {@code null}
     * @param stateQuery     the trigger-time snapshot source (§3.8 / AMD-03), never {@code null}
     * @param clock          the injected clock (§4c), never {@code null}
     * @param config         cascade + auto-disable parameters, never {@code null}
     */
    StandardRunManager(EventPublisher publisher, ActionExecutor actionExecutor,
                       RunConditionGate conditionGate, StateQueryService stateQuery, Clock clock,
                       RunManagerConfig config) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.actionExecutor = Objects.requireNonNull(actionExecutor, "actionExecutor");
        this.conditionGate = Objects.requireNonNull(conditionGate, "conditionGate");
        this.stateQuery = Objects.requireNonNull(stateQuery, "stateQuery");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * The C3 execution-order comparator: priority descending, then {@code automationId}
     * ascending. The subscriber orders a batch of matched automations with this before issuing
     * {@code initiateRun} calls; the FSM itself processes calls in the order received.
     *
     * @return the deterministic execution-order comparator, never {@code null}
     */
    public static Comparator<AutomationDefinition> byExecutionOrder() {
        return Comparator.comparingInt(AutomationDefinition::priority).reversed()
                .thenComparing(AutomationDefinition::automationId);
    }

    @Override
    public Optional<RunId> initiateRun(AutomationDefinition automation,
                                       EventEnvelope triggeringEvent,
                                       List<Integer> matchedTriggers,
                                       Map<String, Set<EntityId>> resolvedTargets,
                                       RunCausalChain parentChain) {
        Objects.requireNonNull(automation, "automation must not be null");
        Objects.requireNonNull(triggeringEvent, "triggeringEvent must not be null");
        Objects.requireNonNull(matchedTriggers, "matchedTriggers must not be null");
        Objects.requireNonNull(resolvedTargets, "resolvedTargets must not be null");
        Objects.requireNonNull(parentChain, "parentChain must not be null");

        AutomationId automationId = automation.automationId();
        EventId triggeringEventId = triggeringEvent.eventId();
        Ulid correlationId = triggeringEvent.causalContext().correlationId();
        Ulid causationId = triggeringEvent.eventId().value();
        Ulid actorRef = automationId.value();                       // B2-C8 envelope stamping (DP-H)
        Instant eventTime = triggeringEvent.eventTime();            // inherited or null (DP-G)
        DedupKey key = new DedupKey(automationId, triggeringEventId);

        // (1) C2 dedup read — separate mechanism from cascade suppression (AMD-91 §4).
        if (dedup.contains(key)) {
            return Optional.empty();
        }
        // (2) Cascade cycle — deterministic chain membership (AMD-91).
        if (parentChain.containsAutomation(automationId)) {
            publishCascadeLoop(automation, triggeringEventId, parentChain,
                    correlationId, causationId, actorRef, eventTime);
            return Optional.empty();
        }
        // (3) Cascade depth.
        if (parentChain.depth() >= config.maxCascadeDepth()) {
            publishCascadeDepthExceeded(automation, triggeringEventId, parentChain,
                    correlationId, causationId, actorRef, eventTime);
            return Optional.empty();
        }
        // (4) Auto-disable — already announced via automation_disabled; suppress silently.
        if (disabled.contains(automationId)) {
            return Optional.empty();
        }

        // (5) Capture the trigger-time snapshot once (DP-A.4 / AMD-03): its viewPosition is the
        // RunContext position and the same snapshot drives the gate's condition evaluation.
        StateSnapshot snapshot = stateQuery.getSnapshot();
        RunId runId = new RunId(UlidFactory.generate(clock));
        RunContext context = new RunContext(
                runId, automationId, triggeringEventId,
                List.copyOf(matchedTriggers), resolvedTargets,
                DefinitionHashes.forDefinition(automation), parentChain,
                snapshot.viewPosition());
        ActiveRun run = new ActiveRun(runId, context, automation, triggeringEvent,
                triggeringEventId, correlationId, causationId, actorRef, eventTime, clock.instant());
        statuses.put(runId, RunStatus.EVALUATING);

        // (6) Dedup CLAIM before the gate (DP-A.3) — a raced duplicate cannot double-publish
        // row 4. Atomic via the concurrent key set; the loser returns empty before the gate.
        if (!dedup.add(key)) {
            statuses.remove(runId);
            return Optional.empty();
        }

        // (7) EVALUATING — conditions BEFORE mode (condition-before-mode contract). The gate
        // runs outside the lock (it performs I/O — the row-4 publish) and publishes row 4.
        if (!conditionGate.conditionsHold(automation, context, triggeringEvent, snapshot)) {
            statuses.put(runId, RunStatus.CONDITION_NOT_MET);
            publishTriggered(run);
            publishCompleted(run, RunStatus.CONDITION_NOT_MET, null, null, 0, 0);
            return Optional.of(runId);                              // a Run, but it consumed no slot
        }

        // (8) Concurrency-mode admission.
        List<ActiveRun> toCancel = new ArrayList<>();
        boolean admitted = false;
        boolean enqueued = false;
        String skipReason = null;
        Ulid skipActiveRunId = null;
        stateLock.lock();
        try {
            if (disabled.contains(automationId)) {
                return Optional.empty();                            // auto-disabled after our claim
            }
            int activeCount = countActive(automationId);
            ModeDecision decision = decide(automation, activeCount);
            if (decision.admit()) {
                if (decision.restartCancel()) {
                    for (ActiveRun victim : activeFor(automationId)) {
                        activeRuns.remove(victim.runId);           // free the slot; victim VT confirms ABORTED
                        toCancel.add(victim);
                    }
                }
                startRun(run);
                admitted = true;
            } else if (decision.enqueue()) {
                pendingQueues.computeIfAbsent(automationId, k -> new ArrayDeque<>()).addLast(run);
                enqueued = true;                                   // status stays EVALUATING (pending)
            } else {
                skipReason = decision.skipReason();
                skipActiveRunId = "mode_busy".equals(skipReason) ? anyActiveRunId(automationId) : null;
            }
        } finally {
            stateLock.unlock();
        }

        if (admitted) {
            for (ActiveRun victim : toCancel) {
                Thread vt = victim.vt;
                if (vt != null) {
                    vt.interrupt();
                }
                publishRunCancelled(victim, triggeringEventId);
            }
            publishTriggered(run);                                  // before the VT — triggered precedes completed
            run.vt.start();
            return Optional.of(runId);
        }
        if (enqueued) {
            return Optional.of(runId);                              // pending; triggered published on drain
        }
        statuses.remove(runId);
        if (skipReason != null) {
            publishRunSkipped(run, skipReason, skipActiveRunId);
        }
        return Optional.empty();
    }

    @Override
    public Optional<RunContext> getActiveRun(RunId runId) {
        Objects.requireNonNull(runId, "runId must not be null");
        ActiveRun run = activeRuns.get(runId);
        return run == null ? Optional.empty() : Optional.of(run.context);
    }

    @Override
    public RunStatus getStatus(RunId runId) {
        Objects.requireNonNull(runId, "runId must not be null");
        RunStatus status = statuses.get(runId);
        if (status == null) {
            throw new IllegalArgumentException("unknown run: " + runId);
        }
        return status;
    }

    @Override
    public int activeRunCount() {
        return activeRuns.size();
    }

    @Override
    public int activeRunCount(AutomationId automationId) {
        Objects.requireNonNull(automationId, "automationId must not be null");
        return countActive(automationId);
    }

    @Override
    public int finalizeZombieRuns(List<ZombieRun> zombies) {
        Objects.requireNonNull(zombies, "zombies must not be null");
        int finalized = 0;
        for (ZombieRun zombie : zombies) {
            if (zombie == null) {
                continue;
            }
            RunId runId = new RunId(zombie.runId());
            stateLock.lock();
            try {
                activeRuns.remove(runId);                           // free any tracked slot
                statuses.put(runId, RunStatus.INTERRUPTED);
            } finally {
                stateLock.unlock();
            }
            // Re-derive the terminal completion from the log — never re-execute (§3.10).
            AutomationCompletedEvent payload = new AutomationCompletedEvent(
                    zombie.runId(), RunStatus.INTERRUPTED.name(), 0L, 0, 0, null,
                    "interrupted_by_crash");
            publish(EventTypes.AUTOMATION_COMPLETED, payload, zombie.automationId(),
                    EventPriority.NORMAL, zombie.correlationId(), zombie.causationId(),
                    zombie.actorRef(), zombie.eventTime());
            finalized++;
        }
        return finalized;
    }

    /**
     * Waits until no Run is active or the timeout elapses, joining live Run VTs one at a time
     * so that queued Runs drained by a completing Run are also observed (DP-A.1). Test seam —
     * {@link Thread#join(long)} is not a banned time access (§4c).
     *
     * @param timeoutMillis the per-Run join budget in milliseconds
     * @throws InterruptedException if the calling thread is interrupted while joining
     */
    void awaitQuiescence(long timeoutMillis) throws InterruptedException {
        for (Thread vt = anyLiveThread(); vt != null; vt = anyLiveThread()) {
            vt.join(timeoutMillis);
            if (liveRunThreads.contains(vt)) {
                return;                                             // join timed out — avoid spinning
            }
        }
    }

    /**
     * Interrupts every active Run's VT (shutdown). Each interrupted Run observes the
     * interruption and finalizes {@code ABORTED}.
     */
    @Override
    public void close() {
        for (Thread vt : List.copyOf(liveRunThreads)) {
            vt.interrupt();
        }
    }

    // ---- Run body (on the VT) ----------------------------------------------

    private void runBody(ActiveRun run) {
        try {
            RunStatus terminal;
            String failureReason = null;
            String abortReason = null;
            int actionCount = 0;
            int commandCount = 0;
            try {
                if (Thread.currentThread().isInterrupted() || !activeRuns.containsKey(run.runId)) {
                    // Cancelled during the admission window before any action ran.
                    terminal = RunStatus.ABORTED;
                    abortReason = "restart_mode";
                } else {
                    ActionExecutionResult result = actionExecutor.execute(
                            run.automation.actions(), run.context, run.triggeringEvent);
                    actionCount = result.actionCount();
                    commandCount = result.commandCount();
                    if (Thread.currentThread().isInterrupted()) {
                        terminal = RunStatus.ABORTED;
                        abortReason = "restart_mode";
                    } else if (result.failed()) {
                        terminal = RunStatus.FAILED;
                        failureReason = result.failureReason();
                    } else {
                        terminal = RunStatus.COMPLETED;
                    }
                }
            } catch (RuntimeException ex) {
                if (Thread.currentThread().isInterrupted()) {
                    terminal = RunStatus.ABORTED;
                    abortReason = "restart_mode";
                } else {
                    terminal = RunStatus.FAILED;
                    failureReason = describe(ex);
                }
            }
            finalizeRun(run, terminal, failureReason, abortReason, actionCount, commandCount);
        } finally {
            // Remove only after the terminal publish so awaitQuiescence observes it.
            liveRunThreads.remove(Thread.currentThread());
        }
    }

    private void finalizeRun(ActiveRun run, RunStatus terminal, String failureReason,
                             String abortReason, int actionCount, int commandCount) {
        AutomationId automationId = run.automation.automationId();
        boolean publishDisabled = false;
        int disableFailureCount = 0;
        String disableLastError = null;
        Ulid disableLastRunId = null;
        ActiveRun drained = null;
        stateLock.lock();
        try {
            activeRuns.remove(run.runId);                           // free the mode slot
            statuses.put(run.runId, terminal);
            if (terminal == RunStatus.FAILED) {
                Instant now = clock.instant();
                Deque<Instant> window = failureWindows.computeIfAbsent(
                        automationId, k -> new ArrayDeque<>());
                window.addLast(now);
                pruneWindow(window, now);
                if (!disabled.contains(automationId)
                        && window.size() >= config.autoDisableThreshold()) {
                    disabled.add(automationId);
                    publishDisabled = true;
                    disableFailureCount = window.size();
                    disableLastError = failureReason;
                    disableLastRunId = run.runId.value();
                }
            }
            drained = drainNext(automationId);                     // QUEUED single-flight drain (DP-A.1)
        } finally {
            stateLock.unlock();
        }
        publishCompleted(run, terminal, failureReason, abortReason, actionCount, commandCount);
        if (publishDisabled) {
            publishDisabledEvent(run, disableFailureCount, disableLastError, disableLastRunId);
        }
        if (drained != null) {
            publishTriggered(drained);                             // outside the lock
            drained.vt.start();
        }
    }

    /**
     * Drains and admits the next queued Run for {@code automationId} if a slot is now free and
     * the automation is not disabled (caller holds {@link #stateLock}). Returns the admitted
     * Run (whose VT the caller starts outside the lock), or {@code null}.
     */
    private ActiveRun drainNext(AutomationId automationId) {
        if (disabled.contains(automationId)) {
            return null;
        }
        Deque<ActiveRun> queue = pendingQueues.get(automationId);
        if (queue == null || queue.isEmpty()) {
            return null;
        }
        ActiveRun next = queue.peekFirst();
        if (countActive(automationId) >= next.automation.maxConcurrent()) {
            return null;
        }
        queue.pollFirst();
        if (queue.isEmpty()) {
            pendingQueues.remove(automationId);
        }
        startRun(next);
        return next;
    }

    /** Creates the Run's VT (unstarted), registers it active and RUNNING (caller holds the lock). */
    private void startRun(ActiveRun run) {
        Thread vt = Thread.ofVirtual()
                .name("automation-run-" + run.automation.automationId() + "-" + run.runId)
                .unstarted(() -> runBody(run));
        run.vt = vt;
        activeRuns.put(run.runId, run);
        liveRunThreads.add(vt);
        statuses.put(run.runId, RunStatus.RUNNING);
    }

    private void pruneWindow(Deque<Instant> window, Instant now) {
        Instant cutoff = now.minus(config.autoDisableWindow());
        while (!window.isEmpty() && window.peekFirst().isBefore(cutoff)) {
            window.removeFirst();
        }
    }

    // ---- Mode-admission decision -------------------------------------------

    private ModeDecision decide(AutomationDefinition automation, int activeCount) {
        return switch (automation.mode()) {
            case SINGLE -> activeCount >= 1
                    ? ModeDecision.skip("mode_busy")
                    : new ModeDecision(true, null, false, false);
            case RESTART -> activeCount >= 1
                    ? new ModeDecision(true, null, true, false)
                    : new ModeDecision(true, null, false, false);
            case QUEUED -> activeCount >= automation.maxConcurrent()
                    ? new ModeDecision(false, null, false, true)
                    : new ModeDecision(true, null, false, false);
            case PARALLEL -> activeCount >= automation.maxConcurrent()
                    ? ModeDecision.skip("queue_full")
                    : new ModeDecision(true, null, false, false);
        };
    }

    private int countActive(AutomationId automationId) {
        int count = 0;
        for (ActiveRun run : activeRuns.values()) {
            if (run.automation.automationId().equals(automationId)) {
                count++;
            }
        }
        return count;
    }

    private List<ActiveRun> activeFor(AutomationId automationId) {
        List<ActiveRun> runs = new ArrayList<>();
        for (ActiveRun run : activeRuns.values()) {
            if (run.automation.automationId().equals(automationId)) {
                runs.add(run);
            }
        }
        return runs;
    }

    private Ulid anyActiveRunId(AutomationId automationId) {
        for (ActiveRun run : activeRuns.values()) {
            if (run.automation.automationId().equals(automationId)) {
                return run.runId.value();
            }
        }
        return null;
    }

    private Thread anyLiveThread() {
        for (Thread vt : liveRunThreads) {
            return vt;
        }
        return null;
    }

    // ---- Publish helpers (always outside the lock) -------------------------

    private void publishTriggered(ActiveRun run) {
        AutomationTriggeredEvent payload = new AutomationTriggeredEvent(
                run.runId.value(),
                run.triggeringEventId,
                triggerIdsOf(run.automation, run.context.matchedTriggers()),
                run.context.resolvedTargets(),
                run.context.definitionHash(),
                run.context.causalChain().depth());
        publish(EventTypes.AUTOMATION_TRIGGERED, payload, run.automation.automationId(),
                EventPriority.NORMAL, run.correlationId, run.causationId, run.actorRef,
                run.eventTime);
    }

    private void publishCompleted(ActiveRun run, RunStatus terminal, String failureReason,
                                  String abortReason, int actionCount, int commandCount) {
        long durationMs = Math.max(0L,
                Duration.between(run.startedAt, clock.instant()).toMillis());
        AutomationCompletedEvent payload = new AutomationCompletedEvent(
                run.runId.value(), terminal.name(), durationMs, actionCount, commandCount,
                failureReason, abortReason);
        publish(EventTypes.AUTOMATION_COMPLETED, payload, run.automation.automationId(),
                EventPriority.NORMAL, run.correlationId, run.causationId, run.actorRef,
                run.eventTime);
    }

    private void publishRunSkipped(ActiveRun run, String reason, Ulid activeRunId) {
        AutomationRunSkippedEvent payload = new AutomationRunSkippedEvent(
                run.automation.automationId(), run.triggeringEventId, reason,
                run.automation.mode().name(), activeRunId,
                run.automation.maxExceededSeverity().name());
        publish(EventTypes.AUTOMATION_RUN_SKIPPED, payload, run.automation.automationId(),
                EventPriority.DIAGNOSTIC, run.correlationId, run.causationId, run.actorRef,
                run.eventTime);
    }

    private void publishRunCancelled(ActiveRun victim, EventId replacingEventId) {
        AutomationRunCancelledEvent payload = new AutomationRunCancelledEvent(
                victim.automation.automationId(), victim.runId.value(), replacingEventId,
                victim.triggeringEventId);
        publish(EventTypes.AUTOMATION_RUN_CANCELLED, payload, victim.automation.automationId(),
                EventPriority.DIAGNOSTIC, victim.correlationId, victim.causationId,
                victim.actorRef, victim.eventTime);
    }

    private void publishDisabledEvent(ActiveRun run, int failureCount, String lastError,
                                      Ulid lastRunId) {
        int windowMinutes = (int) config.autoDisableWindow().toMinutes();
        AutomationDisabledEvent payload = new AutomationDisabledEvent(
                run.automation.automationId(), DISABLE_REASON, failureCount, windowMinutes,
                lastError, lastRunId);
        publish(EventTypes.AUTOMATION_DISABLED, payload, run.automation.automationId(),
                EventPriority.NORMAL, run.correlationId, run.causationId, run.actorRef,
                run.eventTime);
    }

    private void publishCascadeDepthExceeded(AutomationDefinition automation,
                                             EventId triggeringEventId, RunCausalChain parentChain,
                                             Ulid correlationId, Ulid causationId, Ulid actorRef,
                                             Instant eventTime) {
        CascadeDepthExceededEvent payload = new CascadeDepthExceededEvent(
                automation.automationId(), triggeringEventId, parentChain.depth(),
                config.maxCascadeDepth(), correlationId);
        publish(EventTypes.CASCADE_DEPTH_EXCEEDED, payload, automation.automationId(),
                EventPriority.DIAGNOSTIC, correlationId, causationId, actorRef, eventTime);
    }

    private void publishCascadeLoop(AutomationDefinition automation, EventId triggeringEventId,
                                    RunCausalChain parentChain, Ulid correlationId,
                                    Ulid causationId, Ulid actorRef, Instant eventTime) {
        AutomationId automationId = automation.automationId();
        Ulid originalRunId = parentChain.ancestors().stream()
                .filter(link -> link.automationId().equals(automationId))
                .map(link -> link.runId().value())
                .findFirst()
                .orElse(automationId.value());                     // membership is guaranteed by the caller
        List<AutomationId> chain = new ArrayList<>();
        for (RunCausalChain.ChainLink link : parentChain.ancestors()) {
            chain.add(link.automationId());
        }
        chain.add(automationId);                                    // close the cycle path
        CascadeLoopDetectedEvent payload = new CascadeLoopDetectedEvent(
                automationId, triggeringEventId, correlationId, originalRunId, chain);
        publish(EventTypes.CASCADE_LOOP_DETECTED, payload, automationId,
                EventPriority.DIAGNOSTIC, correlationId, causationId, actorRef, eventTime);
    }

    private void publish(String eventType, DomainEvent payload, AutomationId subject,
                         EventPriority priority, Ulid correlationId, Ulid causationId,
                         Ulid actorRef, Instant eventTime) {
        EventDraft draft = new EventDraft(
                eventType, SCHEMA_VERSION, eventTime, SubjectRef.automation(subject),
                priority, EventOrigin.AUTOMATION, payload, actorRef, null);
        try {
            publisher.publish(draft, CausalContext.chain(correlationId, causationId));
        } catch (SequenceConflictException ex) {
            LOG.error("Failed to publish {} for automation {}: sequence conflict",
                    eventType, subject, ex);
        }
    }

    private static String describe(RuntimeException ex) {
        String message = ex.getMessage();
        return (message == null || message.isBlank()) ? ex.getClass().getSimpleName() : message;
    }

    private static List<String> triggerIdsOf(AutomationDefinition automation,
                                             List<Integer> indices) {
        List<TriggerDefinition> triggers = automation.triggers();
        List<String> ids = new ArrayList<>(indices.size());
        for (int index : indices) {
            if (index >= 0 && index < triggers.size()) {
                ids.add(triggerIdOf(triggers.get(index)));
            }
        }
        return ids;
    }

    private static String triggerIdOf(TriggerDefinition trigger) {
        return switch (trigger) {
            case StateChangeTrigger t -> t.triggerId();
            case StateTrigger t -> t.triggerId();
            case EventTrigger t -> t.triggerId();
            case AvailabilityTrigger t -> t.triggerId();
            case NumericThresholdTrigger t -> t.triggerId();
            case CalendarTrigger t -> t.triggerId();
            case ReachabilityTrigger t -> t.triggerId();
            case ManualTrigger t -> t.triggerId();
            case WebhookTrigger t -> t.triggerId();
            case TimeTrigger ignored -> "";
            case SunTrigger ignored -> "";
            case PresenceTrigger ignored -> "";
        };
    }

    // ---- Internal types -----------------------------------------------------

    /** C2 dedup key: one Run per {@code (automation, triggering event)}. */
    private record DedupKey(AutomationId automationId, EventId triggeringEventId) {
    }

    /**
     * The outcome of concurrency-mode admission for one trigger. The {@code admit},
     * {@code restartCancel}, and {@code enqueue} components already define their own
     * accessors, so this record carries only the {@link #skip(String)} factory (no
     * {@code skip} component) and is otherwise built via the canonical constructor.
     */
    private record ModeDecision(boolean admit, String skipReason, boolean restartCancel,
                                boolean enqueue) {

        static ModeDecision skip(String reason) {
            return new ModeDecision(false, reason, false, false);
        }
    }

    /** Mutable per-Run handle held in {@link #activeRuns}/{@link #pendingQueues} and on the Run's VT. */
    private static final class ActiveRun {
        private final RunId runId;
        private final RunContext context;
        private final AutomationDefinition automation;
        private final EventEnvelope triggeringEvent;
        private final EventId triggeringEventId;
        private final Ulid correlationId;
        private final Ulid causationId;
        private final Ulid actorRef;
        private final Instant eventTime;
        private final Instant startedAt;
        private volatile Thread vt;

        private ActiveRun(RunId runId, RunContext context, AutomationDefinition automation,
                          EventEnvelope triggeringEvent, EventId triggeringEventId,
                          Ulid correlationId, Ulid causationId, Ulid actorRef, Instant eventTime,
                          Instant startedAt) {
            this.runId = runId;
            this.context = context;
            this.automation = automation;
            this.triggeringEvent = triggeringEvent;
            this.triggeringEventId = triggeringEventId;
            this.correlationId = correlationId;
            this.causationId = causationId;
            this.actorRef = actorRef;
            this.eventTime = eventTime;
            this.startedAt = startedAt;
        }
    }
}
