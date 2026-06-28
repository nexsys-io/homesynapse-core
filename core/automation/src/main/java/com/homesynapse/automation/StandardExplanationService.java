/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.homesynapse.event.AutomationActionCompletedEvent;
import com.homesynapse.event.AutomationActionStartedEvent;
import com.homesynapse.event.AutomationCompletedEvent;
import com.homesynapse.event.AutomationConditionEvaluatedEvent;
import com.homesynapse.event.AutomationTriggeredEvent;
import com.homesynapse.event.CommandConfirmationTimedOutEvent;
import com.homesynapse.event.CommandIssuedEvent;
import com.homesynapse.event.CommandResultEvent;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventId;
import com.homesynapse.event.EventPage;
import com.homesynapse.event.EventStore;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.StateConfirmedEvent;
import com.homesynapse.platform.identity.AutomationId;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.Ulid;

/**
 * The standard log-derived {@link ExplanationService} (Doc 16 §3.3): assembles run summaries
 * and causal-chain explanations purely by reading the immutable {@link EventStore}. Writes
 * nothing, persists nothing, mints no event (INV-SA-03 / SP2).
 *
 * <p>Stateless and thread-safe over an injected thread-safe {@link EventStore} and
 * {@link AutomationRegistry}; safe for concurrent reads on per-request virtual threads.</p>
 *
 * <h2>Recovering identity and confirmation purely from the log</h2>
 * <ul>
 *   <li>The run-lifecycle event <em>payloads</em> carry no automation id; the producer
 *       publishes them on {@code SubjectRef.automation(...)}, so {@code automationId} is read
 *       from the event <em>envelope</em> subject.</li>
 *   <li>A per-action outcome is derived by following the command by id: a
 *       {@code state_confirmed} whose payload {@code commandEventId} matches the
 *       {@code command_issued} id ⇒ {@code CONFIRMED}; a {@code command_result} failure caused
 *       by the command ⇒ {@code FAILED}; a {@code command_confirmation_timed_out} for the
 *       command ⇒ {@code UNCONFIRMED}; otherwise {@code DISPATCHED}. No device registry is
 *       consulted — a confirmation-disabled command is simply never confirmed in the log, so it
 *       renders {@code DISPATCHED}, never a false {@code CONFIRMED} (the honest-confirmation
 *       guarantee, DP-A2). Keeping the outcome a pure function of the log preserves INV-SA-03
 *       determinism (a mutable registry read at read time would not be replay-safe).</li>
 * </ul>
 *
 * <h2>Ordering and cost</h2>
 * The {@link EventStore} pages forward (ascending {@code globalPosition}) only. {@code listRuns}
 * therefore scans the type-indexed {@code automation_completed} stream and keeps the newest
 * page in an {@code O(limit)} sliding window, reversing for newest-first; {@code explainRun}
 * locates the run's {@code automation_triggered} by a bounded type scan, then reads the bounded
 * correlation. Cost is {@code O(retained-runs-of-that-type)} (the type index, not the whole
 * log); a future backward-read store primitive would make it strictly {@code O(page)}.
 */
final class StandardExplanationService implements ExplanationService {

    private static final Logger LOG = LoggerFactory.getLogger(StandardExplanationService.class);

    /** Page size for the forward type scans (kept modest to bound per-read memory). */
    private static final int SCAN_BATCH = 500;

    /** {@code command_result} outcome that is a protocol ack, not a terminal failure. */
    private static final String OUTCOME_ACKNOWLEDGED = "acknowledged";

    /** {@code automation_action_completed} outcomes for a non-dispatched command action. */
    private static final String ACTION_SKIPPED = "skipped";
    private static final String ACTION_ERROR = "error";

    private final EventStore eventStore;
    private final AutomationRegistry automationRegistry;

    StandardExplanationService(EventStore eventStore, AutomationRegistry automationRegistry) {
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore");
        this.automationRegistry = Objects.requireNonNull(automationRegistry, "automationRegistry");
    }

    @Override
    public RunPage listRuns(Optional<AutomationId> automationId, long beforePosition, int limit) {
        Objects.requireNonNull(automationId, "automationId");
        int cap = Math.max(1, limit);
        long before = beforePosition <= 0 ? Long.MAX_VALUE : beforePosition;

        // Forward scan of the type-indexed terminal markers; keep the newest (cap + 1) below
        // the cursor in a sliding window (ascending). The extra one detects hasMore.
        Deque<EventEnvelope> window = new ArrayDeque<>(cap + 1);
        long after = 0;
        while (true) {
            EventPage page = eventStore.readByType(EventTypes.AUTOMATION_COMPLETED, after, SCAN_BATCH);
            for (EventEnvelope e : page.events()) {
                if (e.globalPosition() >= before) {
                    continue;
                }
                if (automationId.isPresent() && !automationOf(e).equals(automationId.get())) {
                    continue;
                }
                window.addLast(e);
                if (window.size() > cap + 1) {
                    window.removeFirst();
                }
            }
            if (!page.hasMore()) {
                break;
            }
            after = page.nextPosition();
        }

        List<EventEnvelope> ascending = new ArrayList<>(window);
        boolean hasMore = ascending.size() > cap;
        if (hasMore) {
            ascending = ascending.subList(ascending.size() - cap, ascending.size());
        }
        long nextCursorPosition = ascending.isEmpty() ? 0 : ascending.get(0).globalPosition();

        List<EventEnvelope> newestFirst = new ArrayList<>(ascending);
        Collections.reverse(newestFirst);

        List<RunSummary> summaries = new ArrayList<>(newestFirst.size());
        for (EventEnvelope e : newestFirst) {
            RunSummary summary = toSummary(e);
            if (summary != null) {
                summaries.add(summary);
            }
        }
        return new RunPage(summaries, nextCursorPosition, hasMore);
    }

    @Override
    public Optional<RunExplanation> explainRun(RunId runId) {
        Objects.requireNonNull(runId, "runId");

        EventEnvelope triggered = locateTriggered(runId);
        if (triggered == null) {
            return Optional.empty();
        }
        AutomationTriggeredEvent triggerPayload = (AutomationTriggeredEvent) triggered.payload();

        Ulid correlationId = triggered.causalContext().correlationId();
        List<EventEnvelope> chain = eventStore.readByCorrelation(correlationId);

        EventEnvelope completed = firstForRun(chain, EventTypes.AUTOMATION_COMPLETED, runId);
        if (completed == null) {
            // Triggered but not terminal: in-flight runs are out of V1 scope.
            return Optional.empty();
        }
        AutomationCompletedEvent completedPayload = (AutomationCompletedEvent) completed.payload();
        RunStatus status = parseStatus(completedPayload.finalStatus());
        if (status == null) {
            LOG.warn("Skipping run {} with unrecognized finalStatus '{}'",
                    runId, completedPayload.finalStatus());
            return Optional.empty();
        }

        AutomationId automationId = automationOf(triggered);
        Optional<AutomationDefinition> definition = automationRegistry.get(automationId);
        String automationName = definition.map(AutomationDefinition::name).orElse(null);

        RunExplanation.TriggerView trigger = buildTrigger(triggered, triggerPayload, chain, definition);
        List<RunExplanation.ConditionView> conditions = buildConditions(chain, runId);
        List<RunExplanation.ActionView> actions = buildActions(chain, runId);
        RunExplanation.OutcomeView outcome = new RunExplanation.OutcomeView(
                status, firstNonBlank(completedPayload.failureReason(), completedPayload.abortReason()),
                completedPayload.durationMs(), completedPayload.actionCount(),
                completedPayload.commandCount());
        RunExplanation.CascadeView cascade =
                new RunExplanation.CascadeView(null, triggerPayload.cascadeDepth());

        return Optional.of(new RunExplanation(runId, automationId, automationName,
                trigger, conditions, actions, outcome, cascade));
    }

    // ---- run summary --------------------------------------------------------

    private RunSummary toSummary(EventEnvelope completed) {
        AutomationCompletedEvent payload = (AutomationCompletedEvent) completed.payload();
        RunStatus status = parseStatus(payload.finalStatus());
        if (status == null) {
            LOG.warn("Skipping run {} with unrecognized finalStatus '{}'",
                    payload.runId(), payload.finalStatus());
            return null;
        }
        AutomationId automationId = automationOf(completed);
        String automationName =
                automationRegistry.get(automationId).map(AutomationDefinition::name).orElse(null);
        Instant terminalTime = completed.eventTime() != null
                ? completed.eventTime() : completed.ingestTime();
        Instant triggeredAt = terminalTime.minusMillis(Math.max(0L, payload.durationMs()));
        return new RunSummary(new RunId(payload.runId()), automationId, automationName,
                triggeredAt, status, firstNonBlank(payload.failureReason(), payload.abortReason()));
    }

    // ---- trigger ------------------------------------------------------------

    private RunExplanation.TriggerView buildTrigger(EventEnvelope triggered,
                                                    AutomationTriggeredEvent payload,
                                                    List<EventEnvelope> chain,
                                                    Optional<AutomationDefinition> definition) {
        String type = definition
                .filter(d -> !d.triggers().isEmpty())
                .map(d -> d.triggers().get(0).getClass().getSimpleName())
                .orElse(null);
        RunExplanation.SubjectRefView subjectRef = chain.stream()
                .filter(e -> e.eventId().equals(payload.triggeringEventId()))
                .findFirst()
                .map(e -> subjectRefView(e.subjectRef().type().name().toLowerCase(),
                        e.subjectRef().id().toString()))
                .orElse(null);
        Instant matchedAt =
                triggered.eventTime() != null ? triggered.eventTime() : triggered.ingestTime();
        // firingValue is not captured on the lifecycle events in V1 (no payload-type-specific
        // extraction); the field is present and nullable per the frozen shape.
        return new RunExplanation.TriggerView(type, subjectRef, matchedAt, null);
    }

    // ---- conditions ---------------------------------------------------------

    private List<RunExplanation.ConditionView> buildConditions(List<EventEnvelope> chain,
                                                               RunId runId) {
        List<RunExplanation.ConditionView> conditions = new ArrayList<>();
        for (EventEnvelope e : chain) {
            if (!EventTypes.AUTOMATION_CONDITION_EVALUATED.equals(e.eventType())) {
                continue;
            }
            if (!(e.payload() instanceof AutomationConditionEvaluatedEvent p)
                    || !p.runId().equals(runId.value())) {
                continue;
            }
            List<RunExplanation.ObservedStateEntry> observed = new ArrayList<>();
            for (AutomationConditionEvaluatedEvent.EvaluatedEntityState es : p.evaluatedState()) {
                observed.add(new RunExplanation.ObservedStateEntry(
                        es.entityRef().toString(), es.attribute(), es.value()));
            }
            // expression is rendered from conditionType (the YAML text is not on the event);
            // evaluated is true by construction (the event exists only for an evaluated condition).
            conditions.add(new RunExplanation.ConditionView(
                    p.conditionType(), true, p.result(), observed));
        }
        return conditions;
    }

    // ---- actions ------------------------------------------------------------

    private List<RunExplanation.ActionView> buildActions(List<EventEnvelope> chain, RunId runId) {
        // This run's action_started events, in log order, plus its action_completed by index.
        List<EventEnvelope> started = new ArrayList<>();
        Map<Integer, AutomationActionCompletedEvent> completedByIndex = new HashMap<>();
        Map<Integer, Long> completedPositionByIndex = new HashMap<>();
        for (EventEnvelope e : chain) {
            if (e.payload() instanceof AutomationActionStartedEvent p && p.runId().equals(runId.value())) {
                started.add(e);
            } else if (e.payload() instanceof AutomationActionCompletedEvent p
                    && p.runId().equals(runId.value())) {
                completedByIndex.put(p.actionIndex(), p);
                completedPositionByIndex.put(p.actionIndex(), e.globalPosition());
            }
        }

        // command_issued events in this correlation (no runId on the payload — attributed to an
        // action by position window + target membership below).
        List<EventEnvelope> commands = new ArrayList<>();
        for (EventEnvelope e : chain) {
            if (e.payload() instanceof CommandIssuedEvent) {
                commands.add(e);
            }
        }

        List<RunExplanation.ActionView> actions = new ArrayList<>();
        for (EventEnvelope startEnv : started) {
            AutomationActionStartedEvent sp = (AutomationActionStartedEvent) startEnv.payload();
            long startPos = startEnv.globalPosition();
            long endPos = completedPositionByIndex.getOrDefault(sp.actionIndex(), Long.MAX_VALUE);

            List<EventEnvelope> actionCommands = new ArrayList<>();
            for (EventEnvelope c : commands) {
                if (c.globalPosition() <= startPos || c.globalPosition() > endPos) {
                    continue;
                }
                EntityId target = EntityId.of(((CommandIssuedEvent) c.payload()).targetEntityRef());
                if (sp.targetRefs().contains(target)) {
                    actionCommands.add(c);
                }
            }

            if (!actionCommands.isEmpty()) {
                for (EventEnvelope c : actionCommands) {
                    actions.add(commandActionView(sp.actionType(), c, chain));
                }
            } else {
                AutomationActionCompletedEvent cp = completedByIndex.get(sp.actionIndex());
                RunExplanation.ActionView view = nonDispatchedActionView(sp, cp);
                if (view != null) {
                    actions.add(view);
                }
            }
        }
        return actions;
    }

    private RunExplanation.ActionView commandActionView(String actionType, EventEnvelope commandEnv,
                                                        List<EventEnvelope> chain) {
        CommandIssuedEvent ci = (CommandIssuedEvent) commandEnv.payload();
        RunExplanation.SubjectRefView targetRef =
                subjectRefView("entity", ci.targetEntityRef().toString());
        Outcome outcome = deriveOutcome(commandEnv.eventId(), chain);
        return new RunExplanation.ActionView(actionType, targetRef, ci.commandType(),
                ci.parameters(), outcome.value(), outcome.reason());
    }

    /**
     * Surfaces a command action that issued no command: a {@code "skipped"} completion as
     * {@code SKIPPED}, an {@code "error"} completion as {@code FAILED}. Successful non-command
     * actions (delay/wait/branch/emit) are omitted — they are not device commands and have no
     * place in the confirmation-centric outcome vocabulary.
     */
    private RunExplanation.ActionView nonDispatchedActionView(AutomationActionStartedEvent sp,
                                                              AutomationActionCompletedEvent cp) {
        if (cp == null) {
            return null;
        }
        RunExplanation.ActionOutcome outcome;
        if (ACTION_SKIPPED.equals(cp.outcome())) {
            outcome = RunExplanation.ActionOutcome.SKIPPED;
        } else if (ACTION_ERROR.equals(cp.outcome())) {
            outcome = RunExplanation.ActionOutcome.FAILED;
        } else {
            return null;
        }
        RunExplanation.SubjectRefView targetRef = sp.targetRefs().isEmpty()
                ? null
                : subjectRefView("entity", sp.targetRefs().get(0).toString());
        return new RunExplanation.ActionView(sp.actionType(), targetRef, null, "{}",
                outcome, cp.errorDetail());
    }

    // ---- outcome derivation (pure log) --------------------------------------

    private Outcome deriveOutcome(EventId commandEventId, List<EventEnvelope> chain) {
        StateConfirmedEvent confirmed = null;
        CommandResultEvent failure = null;
        CommandConfirmationTimedOutEvent timedOut = null;
        for (EventEnvelope e : chain) {
            switch (e.payload()) {
                case StateConfirmedEvent p -> {
                    if (p.commandEventId().equals(commandEventId)) {
                        confirmed = p;
                    }
                }
                case CommandConfirmationTimedOutEvent p -> {
                    if (p.commandEventId().equals(commandEventId)) {
                        timedOut = p;
                    }
                }
                case CommandResultEvent p -> {
                    if (commandEventId.value().equals(e.causalContext().causationId())
                            && isFailure(p.outcome())) {
                        failure = p;
                    }
                }
                default -> {
                    // not a confirmation-relevant event
                }
            }
        }
        if (confirmed != null) {
            return new Outcome(RunExplanation.ActionOutcome.CONFIRMED, null);
        }
        if (failure != null) {
            return new Outcome(RunExplanation.ActionOutcome.FAILED,
                    firstNonBlank(failure.failureReason(), failure.outcome()));
        }
        if (timedOut != null) {
            return new Outcome(RunExplanation.ActionOutcome.UNCONFIRMED, "confirmation timed out");
        }
        return new Outcome(RunExplanation.ActionOutcome.DISPATCHED, null);
    }

    private static boolean isFailure(String outcome) {
        return outcome != null && !OUTCOME_ACKNOWLEDGED.equals(outcome);
    }

    /** A derived per-action outcome plus its human reason. */
    private record Outcome(RunExplanation.ActionOutcome value, String reason) {
    }

    // ---- lookups ------------------------------------------------------------

    private EventEnvelope locateTriggered(RunId runId) {
        long after = 0;
        while (true) {
            EventPage page = eventStore.readByType(EventTypes.AUTOMATION_TRIGGERED, after, SCAN_BATCH);
            for (EventEnvelope e : page.events()) {
                if (e.payload() instanceof AutomationTriggeredEvent p && p.runId().equals(runId.value())) {
                    return e;
                }
            }
            if (!page.hasMore()) {
                return null;
            }
            after = page.nextPosition();
        }
    }

    private static EventEnvelope firstForRun(List<EventEnvelope> chain, String eventType,
                                             RunId runId) {
        for (EventEnvelope e : chain) {
            if (!eventType.equals(e.eventType())) {
                continue;
            }
            if (e.payload() instanceof AutomationCompletedEvent p && p.runId().equals(runId.value())) {
                return e;
            }
        }
        return null;
    }

    // ---- helpers ------------------------------------------------------------

    private static AutomationId automationOf(EventEnvelope runLifecycleEvent) {
        // The producer publishes run-lifecycle events on SubjectRef.automation(...), so the
        // automation id is the envelope subject (the payload carries none).
        return AutomationId.of(runLifecycleEvent.subjectRef().id());
    }

    private static RunExplanation.SubjectRefView subjectRefView(String type, String id) {
        return new RunExplanation.SubjectRefView(type, id);
    }

    private static RunStatus parseStatus(String name) {
        try {
            return RunStatus.valueOf(name);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        if (b != null && !b.isBlank()) {
            return b;
        }
        return null;
    }
}
