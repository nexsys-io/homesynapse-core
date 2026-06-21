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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Production {@link RunManager} FSM: drives a matched automation and its triggering event
 * through {@code EVALUATING -> RUNNING -> terminal}, enforces deduplication (C2), cascade
 * governance (§3.7.1 / AMD-91), concurrency modes (§3.6), and auto-disable (§6.2), and
 * publishes the run-lifecycle events that make a Run observable (Doc 07 §3.7).
 *
 * <p>Package-private; exposed to the composition root only through
 * {@link RunManagerAssembly}. Drives an injected {@link ActionExecutor} (the real impl is
 * M7.2a-2) and an injected {@link RunConditionGate} (the real condition evaluator + the
 * {@code automation_condition_evaluated} diagnostic are M7.2a-2).</p>
 *
 * <h2>Admission order (Doc 07 §3.4, §3.6; the cross-module condition-before-mode contract)</h2>
 *
 * <ol>
 *   <li><b>Dedup (C2)</b> — key {@code (automationId, triggeringEventId)}; a repeat returns
 *       empty with no event.</li>
 *   <li><b>Cascade cycle</b> — {@code parentChain.containsAutomation(...)} suppresses and
 *       publishes {@code cascade_loop_detected} (AMD-91 chain membership).</li>
 *   <li><b>Cascade depth</b> — {@code parentChain.depth() >= max} suppresses and publishes
 *       {@code cascade_depth_exceeded}.</li>
 *   <li><b>Auto-disable</b> — a disabled automation is suppressed silently (it published
 *       {@code automation_disabled} when it was disabled).</li>
 *   <li><b>EVALUATING</b> — the condition gate runs <em>before</em> mode enforcement; a Run
 *       whose conditions are false terminates {@code CONDITION_NOT_MET} immediately and
 *       <em>never consumes a mode slot</em> (prevents a {@code SINGLE} automation from
 *       blocking itself on failed conditions).</li>
 *   <li><b>Concurrency-mode admission (§3.6)</b> — drop ({@code automation_run_skipped}) or,
 *       for {@code RESTART}, cancel the active Run ({@code automation_run_cancelled}).</li>
 *   <li><b>Admit</b> — register active, publish {@code automation_triggered} (closing the
 *       M7.1 C1-interim hold), spawn the Run's virtual thread.</li>
 * </ol>
 *
 * <p><strong>Concurrency (LTD-11 / LTD-01).</strong> Compound admission/terminal updates
 * are serialized by a {@link ReentrantLock} — never {@code synchronized} (virtual threads
 * pin on {@code synchronized}). Event publication always happens <em>outside</em> the lock
 * (no I/O under a lock). Each Run executes on its own virtual thread; {@code RESTART}
 * cancellation and shutdown use {@link Thread#interrupt()}.</p>
 *
 * <p><strong>Determinism (AMD-91-INV-01).</strong> Cascade suppression is a pure function
 * of {@code parentChain} plus config — no windowed or evictable state participates.</p>
 *
 * <p><strong>Time (§4c).</strong> All timing derives from the injected {@link Clock};
 * never {@code Instant.now()}/{@code System.*}. New {@link RunId}s are generated from the
 * clock via {@link UlidFactory#generate(Clock)}.</p>
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
    private final Clock clock;
    private final RunManagerConfig config;

    /**
     * Serializes compound admission/terminal state mutations (active-run set, dedup claim,
     * auto-disable window). Never {@code synchronized} (LTD-11).
     */
    private final ReentrantLock stateLock = new ReentrantLock();

    /** Active Runs (admitted, not yet terminal), keyed by {@link RunId}. */
    private final Map<RunId, ActiveRun> activeRuns = new ConcurrentHashMap<>();

    /** Run status including terminal states; retained for status queries (no eviction here). */
    private final Map<RunId, RunStatus> statuses = new ConcurrentHashMap<>();

    /** C2 dedup: {@code (automationId, triggeringEventId)} pairs that became Runs. */
    private final Set<DedupKey> dedup = ConcurrentHashMap.newKeySet();

    /** Per-automation failure timestamps within the auto-disable window; guarded by {@link #stateLock}. */
    private final Map<AutomationId, Deque<Instant>> failureWindows = new HashMap<>();

    /** Automations currently auto-disabled (triggers suppressed until operator re-enable). */
    private final Set<AutomationId> disabled = ConcurrentHashMap.newKeySet();

    /**
     * Every live Run VT (including {@code RESTART} victims that have been removed from
     * {@link #activeRuns} to free their slot but are still finalizing). Used by
     * {@link #close()} for a complete shutdown and by {@link #awaitQuiescence(long)} so a
     * cancelled Run's terminal publish is observed deterministically.
     */
    private final Set<Thread> liveRunThreads = ConcurrentHashMap.newKeySet();

    /**
     * Constructs the FSM against its injected seams.
     *
     * @param publisher      the durable event publish surface, never {@code null}
     * @param actionExecutor the RUNNING-state executor (M7.2a-2 impl), never {@code null}
     * @param conditionGate  the EVALUATING-state gate (M7.2a-2 impl), never {@code null}
     * @param clock          the injected clock (§4c), never {@code null}
     * @param config         cascade + auto-disable parameters, never {@code null}
     */
    StandardRunManager(EventPublisher publisher, ActionExecutor actionExecutor,
                       RunConditionGate conditionGate, Clock clock, RunManagerConfig config) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.actionExecutor = Objects.requireNonNull(actionExecutor, "actionExecutor");
        this.conditionGate = Objects.requireNonNull(conditionGate, "conditionGate");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * The C3 execution-order comparator: priority descending, then {@code automationId}
     * ascending. The subscriber (M7.2a-2 / wiring) orders a batch of matched automations
     * with this before issuing {@code initiateRun} calls; the FSM itself processes calls in
     * the order received.
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

        // (1) C2 dedup — separate mechanism from cascade suppression (AMD-91 §4).
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

        // Build the (tentative) Run; the gate needs a RunContext.
        RunId runId = new RunId(UlidFactory.generate(clock));
        RunContext context = new RunContext(
                runId, automationId, triggeringEventId,
                List.copyOf(matchedTriggers), resolvedTargets,
                DefinitionHashes.forDefinition(automation), parentChain,
                0L);                                                // snapshot position: M7.2a-2 wires the real value
        ActiveRun run = new ActiveRun(runId, context, automation, triggeringEventId,
                correlationId, causationId, actorRef, eventTime, clock.instant());
        statuses.put(runId, RunStatus.EVALUATING);

        // (5) EVALUATING — conditions BEFORE mode (condition-before-mode contract). The gate
        // runs outside the lock (it may perform I/O in M7.2a-2).
        if (!conditionGate.conditionsHold(automation, context)) {
            if (!dedup.add(key)) {                                  // lost a concurrent dedup race
                statuses.remove(runId);
                return Optional.empty();
            }
            statuses.put(runId, RunStatus.CONDITION_NOT_MET);
            publishTriggered(run);
            publishCompleted(run, RunStatus.CONDITION_NOT_MET, null, null);
            return Optional.of(runId);                              // a Run, but it consumed no slot
        }

        // (6) Concurrency-mode admission.
        List<ActiveRun> toCancel = new ArrayList<>();
        boolean admitted = false;
        String skipReason = null;
        Ulid skipActiveRunId = null;
        stateLock.lock();
        try {
            if (dedup.contains(key) || disabled.contains(automationId)) {
                return Optional.empty();                            // raced with a concurrent admit/disable
            }
            int activeCount = countActive(automationId);
            ModeDecision decision = decide(automation, activeCount);
            if (decision.admit()) {
                if (dedup.add(key)) {                              // claim before mutating any state
                    if (decision.restartCancel()) {
                        for (ActiveRun victim : activeFor(automationId)) {
                            activeRuns.remove(victim.runId);       // free the slot; victim VT confirms ABORTED
                            toCancel.add(victim);
                        }
                    }
                    Thread vt = Thread.ofVirtual()
                            .name("automation-run-" + automationId + "-" + runId)
                            .unstarted(() -> runBody(run));
                    run.vt = vt;
                    activeRuns.put(runId, run);
                    liveRunThreads.add(vt);
                    statuses.put(runId, RunStatus.RUNNING);
                    admitted = true;
                }
                // else: lost the dedup race — nothing mutated; handled as a silent empty below
            } else {
                skipReason = decision.skipReason();
                skipActiveRunId = "mode_busy".equals(skipReason) ? anyActiveRunId(automationId) : null;
            }
        } finally {
            stateLock.unlock();
        }

        if (!admitted) {
            statuses.remove(runId);
            if (skipReason != null) {
                publishRunSkipped(run, skipReason, skipActiveRunId);
            }
            return Optional.empty();
        }

        // Cancel RESTART victims outside the lock: interrupt + publish (their VTs finalize ABORTED).
        for (ActiveRun victim : toCancel) {
            Thread vt = victim.vt;
            if (vt != null) {
                vt.interrupt();
            }
            publishRunCancelled(victim, triggeringEventId);
        }

        // Publish automation_triggered before starting the VT — guarantees triggered precedes completed.
        publishTriggered(run);
        run.vt.start();
        return Optional.of(runId);
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
     * Waits until no Run is active or the timeout elapses, by joining the active Runs' VTs.
     * Test seam for deterministic observation of asynchronous terminal transitions —
     * {@link Thread#join(long)} is not a banned time access (§4c).
     *
     * @param timeoutMillis the per-Run join budget in milliseconds
     * @throws InterruptedException if the calling thread is interrupted while joining
     */
    void awaitQuiescence(long timeoutMillis) throws InterruptedException {
        for (Thread vt : List.copyOf(liveRunThreads)) {
            vt.join(timeoutMillis);
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
            try {
                if (Thread.currentThread().isInterrupted() || !activeRuns.containsKey(run.runId)) {
                    // Cancelled during the admission window before any action ran.
                    terminal = RunStatus.ABORTED;
                    abortReason = "restart_mode";
                } else {
                    actionExecutor.execute(run.automation.actions(), run.context);
                    if (Thread.currentThread().isInterrupted()) {
                        terminal = RunStatus.ABORTED;
                        abortReason = "restart_mode";
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
            finalizeRun(run, terminal, failureReason, abortReason);
        } finally {
            // Remove only after the terminal publish so awaitQuiescence observes it.
            liveRunThreads.remove(Thread.currentThread());
        }
    }

    private void finalizeRun(ActiveRun run, RunStatus terminal, String failureReason,
                             String abortReason) {
        AutomationId automationId = run.automation.automationId();
        boolean publishDisabled = false;
        int disableFailureCount = 0;
        String disableLastError = null;
        Ulid disableLastRunId = null;
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
        } finally {
            stateLock.unlock();
        }
        // actionCount/commandCount are 0 in M7.2a-1: the stub ActionExecutor reports no
        // tallies (the interface returns void). M7.2a-2 carries the real tallies.
        publishCompleted(run, terminal, failureReason, abortReason);
        if (publishDisabled) {
            publishDisabledEvent(run, disableFailureCount, disableLastError, disableLastRunId);
        }
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
                    ? new ModeDecision(false, "mode_busy", false)
                    : new ModeDecision(true, null, false);
            case RESTART -> activeCount >= 1
                    ? new ModeDecision(true, null, true)
                    : new ModeDecision(true, null, false);
            case QUEUED, PARALLEL -> activeCount >= automation.maxConcurrent()
                    ? new ModeDecision(false, "queue_full", false)
                    : new ModeDecision(true, null, false);
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
                                  String abortReason) {
        long durationMs = Math.max(0L,
                Duration.between(run.startedAt, clock.instant()).toMillis());
        AutomationCompletedEvent payload = new AutomationCompletedEvent(
                run.runId.value(), terminal.name(), durationMs, 0, 0, failureReason, abortReason);
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

    /** The outcome of concurrency-mode admission for one trigger. */
    private record ModeDecision(boolean admit, String skipReason, boolean restartCancel) {
    }

    /** Mutable per-Run handle held in {@link #activeRuns} and on the Run's VT. */
    private static final class ActiveRun {
        private final RunId runId;
        private final RunContext context;
        private final AutomationDefinition automation;
        private final EventId triggeringEventId;
        private final Ulid correlationId;
        private final Ulid causationId;
        private final Ulid actorRef;
        private final Instant eventTime;
        private final Instant startedAt;
        private volatile Thread vt;

        private ActiveRun(RunId runId, RunContext context, AutomationDefinition automation,
                          EventId triggeringEventId, Ulid correlationId, Ulid causationId,
                          Ulid actorRef, Instant eventTime, Instant startedAt) {
            this.runId = runId;
            this.context = context;
            this.automation = automation;
            this.triggeringEventId = triggeringEventId;
            this.correlationId = correlationId;
            this.causationId = causationId;
            this.actorRef = actorRef;
            this.eventTime = eventTime;
            this.startedAt = startedAt;
        }
    }
}
