/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import com.homesynapse.event.AutomationActionCompletedEvent;
import com.homesynapse.event.AutomationActionStartedEvent;
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
import com.homesynapse.state.Availability;
import com.homesynapse.state.EntityState;
import com.homesynapse.state.StateQueryService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Production {@link ActionExecutor}: runs the five Tier-1 action types sequentially on the
 * Run's virtual thread, emitting {@code automation_action_started}/{@code _completed} (AMD-92
 * rows 5/6) per top-level action and returning the real action/command tally (Doc 07 §3.9).
 *
 * <h2>Action handling</h2>
 * <ul>
 *   <li>{@link CommandAction} — resolve the target selector, apply {@link UnavailablePolicy}
 *       per target ({@code SKIP} skips only the unavailable target, {@code ERROR} fails the
 *       Run, {@code WARN} dispatches anyway), dispatch via {@link CommandDispatchService}, and
 *       count each dispatch.</li>
 *   <li>{@link DelayAction} — {@code Thread.sleep(Duration)} on the VT (no carrier pinning).</li>
 *   <li>{@link WaitForAction} — poll the condition against a fresh snapshot until it holds or
 *       the timeout elapses (clock-driven, §4c); a timeout completes the action (not an
 *       error).</li>
 *   <li>{@link ConditionBranchAction} — evaluate inline against a fresh snapshot and execute
 *       the chosen branch's actions for their effects (the branch's row 5/6 brackets them;
 *       nested actions do not emit their own row 5/6 at the Tier-1 floor).</li>
 *   <li>{@link EmitEventAction} — publish the user-defined event via {@link EmittedDomainEvent}.</li>
 * </ul>
 *
 * <p>The three Tier-2 permits throw {@link UnsupportedOperationException} (DP-C) via the
 * exhaustive no-{@code default} switch, which propagates so a misconfigured Tier-2 action is
 * loud, not silent. Any other action failure stops the sequence (§6.2 fail-fast): the failing
 * action's row 6 carries {@code outcome="error"} + {@code errorDetail} and the returned tally
 * carries the failure reason, so the FSM terminates the Run {@code FAILED} (DP-G). An
 * interrupt (RESTART cancellation) restores the interrupt flag and returns, so the FSM
 * finalizes {@code ABORTED}.</p>
 *
 * <p>Thread-safe per-Run; the action diagnostics publish on the triggering event's
 * {@code CausalContext} (AMD-92 §2.4). Stateless apart from its injected collaborators.</p>
 */
public final class StandardActionExecutor implements ActionExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(StandardActionExecutor.class);

    private static final int SCHEMA_VERSION = 1;

    /** Poll cadence for {@link WaitForAction} when the action omits an explicit interval. */
    static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(500);

    private final CommandDispatchService dispatchService;
    private final SelectorResolver selectorResolver;
    private final ConditionEvaluator conditionEvaluator;
    private final StateQueryService stateQuery;
    private final EventPublisher publisher;
    private final Clock clock;

    /**
     * Constructs the executor against its injected collaborators.
     *
     * @param dispatchService    routes command actions to integrations, never {@code null}
     * @param selectorResolver   resolves command/branch target selectors, never {@code null}
     * @param conditionEvaluator evaluates wait-for / branch conditions, never {@code null}
     * @param stateQuery         supplies fresh snapshots for wait-for / branch evaluation,
     *                           never {@code null}
     * @param publisher          the durable event publish surface, never {@code null}
     * @param clock              the injected clock for wait-for timeouts (§4c), never
     *                           {@code null}
     */
    public StandardActionExecutor(CommandDispatchService dispatchService,
                                  SelectorResolver selectorResolver,
                                  ConditionEvaluator conditionEvaluator,
                                  StateQueryService stateQuery, EventPublisher publisher,
                                  Clock clock) {
        this.dispatchService = Objects.requireNonNull(dispatchService, "dispatchService");
        this.selectorResolver = Objects.requireNonNull(selectorResolver, "selectorResolver");
        this.conditionEvaluator = Objects.requireNonNull(conditionEvaluator, "conditionEvaluator");
        this.stateQuery = Objects.requireNonNull(stateQuery, "stateQuery");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ActionExecutionResult execute(List<ActionDefinition> actions, RunContext context,
                                         EventEnvelope triggeringEvent) {
        Objects.requireNonNull(actions, "actions must not be null");
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(triggeringEvent, "triggeringEvent must not be null");

        AutomationId automationId = context.automationId();
        int actionCount = 0;
        int commandCount = 0;
        for (int index = 0; index < actions.size(); index++) {
            ActionDefinition action = actions.get(index);
            publishStarted(automationId, context, index, action, topLevelTargets(action),
                    triggeringEvent);
            actionCount++;
            try {
                commandCount += applyAction(action, context, triggeringEvent);
            } catch (UnsupportedOperationException ex) {
                throw ex;                                           // Tier 2 reserved — propagate (DP-C)
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();                 // restore — FSM finalizes ABORTED
                publishCompleted(automationId, context, index, "skipped", null, triggeringEvent);
                return ActionExecutionResult.succeeded(actionCount, commandCount);
            } catch (RuntimeException ex) {
                String detail = describe(ex);                       // §6.2 fail-fast
                publishCompleted(automationId, context, index, "error", detail, triggeringEvent);
                return ActionExecutionResult.failed(actionCount, commandCount, detail);
            }
            publishCompleted(automationId, context, index, "success", null, triggeringEvent);
        }
        return ActionExecutionResult.succeeded(actionCount, commandCount);
    }

    /** Performs one action's effect, returning the commands it dispatched. No row 5/6. */
    private int applyAction(ActionDefinition action, RunContext context,
                            EventEnvelope triggeringEvent) throws InterruptedException {
        return switch (action) {
            case CommandAction command -> dispatchCommand(command, context);
            case DelayAction delay -> {
                Thread.sleep(delay.duration());
                yield 0;
            }
            case WaitForAction waitFor -> {
                awaitCondition(waitFor);
                yield 0;
            }
            case ConditionBranchAction branch -> applyBranch(branch, context, triggeringEvent);
            case EmitEventAction emit -> {
                publishEmittedEvent(emit, context, triggeringEvent);
                yield 0;
            }
            case ActivateSceneAction ignored ->
                    throw new UnsupportedOperationException("Tier 2 action reserved: ActivateSceneAction");
            case InvokeIntegrationAction ignored ->
                    throw new UnsupportedOperationException("Tier 2 action reserved: InvokeIntegrationAction");
            case ParallelAction ignored ->
                    throw new UnsupportedOperationException("Tier 2 action reserved: ParallelAction");
        };
    }

    private int dispatchCommand(CommandAction action, RunContext context) {
        EventId commandEventId = context.triggeringEventId();
        int dispatched = 0;
        for (EntityId target : selectorResolver.resolve(action.target())) {
            if (availabilityOf(target) == Availability.UNAVAILABLE) {
                switch (action.onUnavailable()) {
                    case SKIP -> {
                        continue;                                   // skip only this target (§3.9)
                    }
                    case ERROR -> throw new IllegalStateException(
                            "Target '" + target + "' is unavailable");
                    case WARN -> {
                        // dispatch anyway
                    }
                }
            }
            dispatchService.dispatch(commandEventId, target, action.commandName(),
                    action.parameters());
            dispatched++;
        }
        return dispatched;
    }

    private int applyBranch(ConditionBranchAction branch, RunContext context,
                            EventEnvelope triggeringEvent) throws InterruptedException {
        boolean taken = conditionEvaluator.evaluate(branch.condition(), stateQuery.getSnapshot());
        List<ActionDefinition> chosen = taken ? branch.thenActions() : branch.elseActions();
        int commands = 0;
        for (ActionDefinition nested : chosen) {
            commands += applyAction(nested, context, triggeringEvent);
        }
        return commands;
    }

    private void awaitCondition(WaitForAction action) throws InterruptedException {
        Instant deadline = clock.instant().plus(action.timeout());
        Duration poll = action.pollInterval() != null ? action.pollInterval() : DEFAULT_POLL_INTERVAL;
        while (true) {
            if (conditionEvaluator.evaluate(action.condition(), stateQuery.getSnapshot())) {
                return;                                             // condition met
            }
            if (!clock.instant().isBefore(deadline)) {
                return;                                             // timed out — completes, not an error
            }
            Thread.sleep(poll);
        }
    }

    private Availability availabilityOf(EntityId target) {
        return stateQuery.getState(target).map(EntityState::availability)
                .orElse(Availability.UNKNOWN);
    }

    private List<EntityId> topLevelTargets(ActionDefinition action) {
        if (action instanceof CommandAction command) {
            return List.copyOf(selectorResolver.resolve(command.target()));
        }
        return List.of();
    }

    private void publishStarted(AutomationId automationId, RunContext context, int index,
                                ActionDefinition action, List<EntityId> targets,
                                EventEnvelope triggeringEvent) {
        AutomationActionStartedEvent payload = new AutomationActionStartedEvent(
                context.runId().value(), index, action.getClass().getSimpleName(), targets);
        publishDiagnostic(EventTypes.AUTOMATION_ACTION_STARTED, payload, automationId,
                triggeringEvent);
    }

    private void publishCompleted(AutomationId automationId, RunContext context, int index,
                                  String outcome, String errorDetail,
                                  EventEnvelope triggeringEvent) {
        AutomationActionCompletedEvent payload = new AutomationActionCompletedEvent(
                context.runId().value(), index, outcome, errorDetail);
        publishDiagnostic(EventTypes.AUTOMATION_ACTION_COMPLETED, payload, automationId,
                triggeringEvent);
    }

    private void publishEmittedEvent(EmitEventAction action, RunContext context,
                                     EventEnvelope triggeringEvent) {
        AutomationId automationId = context.automationId();
        EmittedDomainEvent payload = new EmittedDomainEvent(action.eventType(), action.payload());
        EventDraft draft = new EventDraft(action.eventType(), SCHEMA_VERSION,
                triggeringEvent.eventTime(), SubjectRef.automation(automationId),
                EventPriority.NORMAL, EventOrigin.AUTOMATION, payload, automationId.value(), null);
        publishDraft(draft, triggeringEvent);
    }

    private void publishDiagnostic(String eventType, DomainEvent payload, AutomationId automationId,
                                   EventEnvelope triggeringEvent) {
        EventDraft draft = new EventDraft(eventType, SCHEMA_VERSION, triggeringEvent.eventTime(),
                SubjectRef.automation(automationId), EventPriority.DIAGNOSTIC,
                EventOrigin.AUTOMATION, payload, automationId.value(), null);
        publishDraft(draft, triggeringEvent);
    }

    private void publishDraft(EventDraft draft, EventEnvelope triggeringEvent) {
        try {
            publisher.publish(draft, CausalContext.chain(
                    triggeringEvent.causalContext().correlationId(),
                    triggeringEvent.eventId().value()));
        } catch (SequenceConflictException ex) {
            LOG.error("Failed to publish {}: sequence conflict", draft.eventType(), ex);
        }
    }

    private static String describe(RuntimeException ex) {
        String message = ex.getMessage();
        return (message == null || message.isBlank()) ? ex.getClass().getSimpleName() : message;
    }
}
