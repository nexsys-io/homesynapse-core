/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import com.homesynapse.device.CapabilityInstance;
import com.homesynapse.device.CommandDefinition;
import com.homesynapse.device.Entity;
import com.homesynapse.device.EntityRegistry;
import com.homesynapse.device.IdempotencyClass;
import com.homesynapse.event.AutomationActionCompletedEvent;
import com.homesynapse.event.AutomationActionStartedEvent;
import com.homesynapse.event.CausalContext;
import com.homesynapse.event.CommandIdempotency;
import com.homesynapse.event.CommandIssuedEvent;
import com.homesynapse.event.DomainEvent;
import com.homesynapse.event.EventDraft;
import com.homesynapse.event.EventEnvelope;
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
 *       Run, {@code WARN} emits anyway), and <strong>emit one {@code command_issued} event per
 *       target</strong> — the substrate-native command hop (§1 D1 / AMD-95): the log is the
 *       single source of truth for dispatch, and the co-located {@code command_dispatch_service}
 *       subscriber consumes {@code command_issued} and routes it (M7.4a). The executor no longer
 *       dispatches in-process. Each emit counts toward the command tally.</li>
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
 * <h2>The {@code command_issued} producer (M7.4a)</h2>
 * <p>Each command target yields exactly one {@code command_issued} carrying the frozen
 * 5-component {@link CommandIssuedEvent} (AMD-95 §2.B/§2.C): the target ULID, the command type,
 * the serialized parameters, the resolved {@code confirmationTimeoutMs}, and the resolved
 * {@link CommandIdempotency}. The timeout follows the capability precedence — the target's
 * {@link CommandDefinition#defaultTimeout()} with the injected
 * {@code automation.command_pipeline.default_confirmation_timeout_ms} fallback (Doc 07 §9) — and
 * the idempotency is mapped from the same {@link CommandDefinition}; both fall back when no
 * capability defines the command (the dispatch subscriber then rejects it {@code unroutable} /
 * {@code invalid}). {@code command_issued} carries <em>no</em> expectation/confirmation/policy —
 * the ledger (M7.4b) resolves the expectation from the capability. The event publishes on the
 * triggering event's {@code CausalContext} (correlation = the Run's, causation = the triggering
 * event — Doc 07 §3.11.2), preserving the per-Run issue order (AMD-31; the subscriber then
 * dispatches in {@code global_position} order).</p>
 *
 * <p>The parameters JSON is produced by an injected serializer: {@code com.homesynapse.automation}
 * carries no JSON library (Jackson lives in persistence/config/app), so the composition root —
 * which owns the persistence {@code ObjectMapper} — supplies the serializer, keeping command
 * parameters round-trip-faithful with the same serialization the persistence layer and the
 * future integration adapter use, without a new module edge or a hand-rolled JSON writer.</p>
 *
 * <p>The three Tier-2 permits throw {@link UnsupportedOperationException} (DP-C) via the
 * exhaustive no-{@code default} switch, which propagates so a misconfigured Tier-2 action is
 * loud, not silent. Any other action failure stops the sequence (§6.2 fail-fast): the failing
 * action's row 6 carries {@code outcome="error"} + {@code errorDetail} and the returned tally
 * carries the failure reason, so the FSM terminates the Run {@code FAILED} (DP-G). An
 * interrupt (RESTART cancellation) restores the interrupt flag and returns, so the FSM
 * finalizes {@code ABORTED}.</p>
 *
 * <p>Thread-safe per-Run; the action diagnostics and the {@code command_issued} emits publish on
 * the triggering event's {@code CausalContext} (AMD-92 §2.4). Stateless apart from its injected
 * collaborators.</p>
 */
public final class StandardActionExecutor implements ActionExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(StandardActionExecutor.class);

    private static final int SCHEMA_VERSION = 1;

    /** The JSON object literal for a parameterless command (the non-blank floor, AMD-95). */
    private static final String EMPTY_PARAMETERS_JSON = "{}";

    /** Poll cadence for {@link WaitForAction} when the action omits an explicit interval. */
    static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(500);

    private final EntityRegistry entityRegistry;
    private final SelectorResolver selectorResolver;
    private final ConditionEvaluator conditionEvaluator;
    private final StateQueryService stateQuery;
    private final EventPublisher publisher;
    private final Clock clock;
    private final long defaultConfirmationTimeoutMs;
    private final Function<Map<String, Object>, String> parameterSerializer;

    /**
     * Constructs the executor against its injected collaborators.
     *
     * @param entityRegistry               resolves a command target to its capability (the
     *                                     {@link CommandDefinition} that sources the timeout +
     *                                     idempotency on {@code command_issued}), never
     *                                     {@code null}
     * @param selectorResolver             resolves command/branch target selectors, never
     *                                     {@code null}
     * @param conditionEvaluator           evaluates wait-for / branch conditions, never
     *                                     {@code null}
     * @param stateQuery                   supplies fresh snapshots for wait-for / branch
     *                                     evaluation and per-target availability, never
     *                                     {@code null}
     * @param publisher                    the durable event publish surface, never {@code null}
     * @param clock                        the injected clock for wait-for timeouts (§4c), never
     *                                     {@code null}
     * @param defaultConfirmationTimeoutMs the fallback {@code confirmationTimeoutMs} when the
     *                                     target capability declares no positive
     *                                     {@code default_timeout}
     *                                     ({@code automation.command_pipeline.default_confirmation_timeout_ms},
     *                                     Doc 07 §9); must be {@code > 0}
     * @param parameterSerializer          serializes a command's parameter map to its JSON object
     *                                     string (the composition root supplies the persistence
     *                                     {@code ObjectMapper}-backed serializer), never
     *                                     {@code null}
     * @throws NullPointerException     if any reference argument is {@code null}
     * @throws IllegalArgumentException if {@code defaultConfirmationTimeoutMs <= 0}
     */
    public StandardActionExecutor(EntityRegistry entityRegistry,
                                  SelectorResolver selectorResolver,
                                  ConditionEvaluator conditionEvaluator,
                                  StateQueryService stateQuery, EventPublisher publisher,
                                  Clock clock, long defaultConfirmationTimeoutMs,
                                  Function<Map<String, Object>, String> parameterSerializer) {
        this.entityRegistry = Objects.requireNonNull(entityRegistry, "entityRegistry");
        this.selectorResolver = Objects.requireNonNull(selectorResolver, "selectorResolver");
        this.conditionEvaluator = Objects.requireNonNull(conditionEvaluator, "conditionEvaluator");
        this.stateQuery = Objects.requireNonNull(stateQuery, "stateQuery");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (defaultConfirmationTimeoutMs <= 0) {
            throw new IllegalArgumentException(
                    "defaultConfirmationTimeoutMs must be positive: " + defaultConfirmationTimeoutMs);
        }
        this.defaultConfirmationTimeoutMs = defaultConfirmationTimeoutMs;
        this.parameterSerializer = Objects.requireNonNull(parameterSerializer, "parameterSerializer");
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
            // FIX-2b-ii (i), line D: the Run's first act per action, named before the row-5
            // publish — a stall between the hand-off and here is the executor's, not the bus's.
            LOG.info("automation.action_step_started: runId={} automationId={} index={} type={}",
                    context.runId().value(), automationId, index,
                    action.getClass().getSimpleName());
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

    /** Performs one action's effect, returning the commands it issued. No row 5/6. */
    private int applyAction(ActionDefinition action, RunContext context,
                            EventEnvelope triggeringEvent) throws InterruptedException {
        return switch (action) {
            case CommandAction command -> issueCommands(command, context, triggeringEvent);
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

    /**
     * Emits one {@code command_issued} per resolved, dispatchable target (§3.9 / §3.11; §1 D1).
     * Applies {@link UnavailablePolicy} per target, then publishes the frozen 5-component event;
     * returns the number of commands issued.
     */
    private int issueCommands(CommandAction action, RunContext context,
                              EventEnvelope triggeringEvent) {
        AutomationId automationId = context.automationId();
        int issued = 0;
        for (EntityId target : selectorResolver.resolve(action.target())) {
            if (availabilityOf(target) == Availability.UNAVAILABLE) {
                switch (action.onUnavailable()) {
                    case SKIP -> {
                        continue;                                   // skip only this target (§3.9)
                    }
                    case ERROR -> throw new IllegalStateException(
                            "Target '" + target + "' is unavailable");
                    case WARN -> {
                        // emit anyway
                    }
                }
            }
            emitCommandIssued(automationId, target, action, triggeringEvent);
            issued++;
        }
        return issued;
    }

    /**
     * Builds and publishes a single {@code command_issued} for {@code target}. The timeout +
     * idempotency are resolved from the target's capability {@link CommandDefinition} (with the
     * config fallback); the parameters are serialized via the injected serializer.
     */
    private void emitCommandIssued(AutomationId automationId, EntityId target, CommandAction action,
                                   EventEnvelope triggeringEvent) {
        Optional<CommandDefinition> definition =
                resolveCommandDefinition(target, action.commandName());
        int timeoutMs = resolveTimeoutMs(definition);
        CommandIdempotency idempotency = definition
                .map(d -> mapIdempotency(d.idempotencyClass()))
                .orElse(CommandIdempotency.NOT_IDEMPOTENT);     // safe default — never silently re-fire
        CommandIssuedEvent payload = new CommandIssuedEvent(target.value(), action.commandName(),
                serializeParameters(action.parameters()), timeoutMs, idempotency);
        EventDraft draft = new EventDraft(EventTypes.COMMAND_ISSUED, SCHEMA_VERSION,
                triggeringEvent.eventTime(), SubjectRef.entity(target), EventPriority.NORMAL,
                EventOrigin.AUTOMATION, payload, automationId.value(), null);
        publishDraft(draft, triggeringEvent);
    }

    /** The target capability's command definition for {@code commandName}, if any declares it. */
    private Optional<CommandDefinition> resolveCommandDefinition(EntityId target,
                                                                String commandName) {
        Optional<Entity> entity = entityRegistry.findEntity(target);
        if (entity.isEmpty()) {
            return Optional.empty();
        }
        for (CapabilityInstance instance : entity.get().capabilities()) {
            CommandDefinition definition = instance.commands().get(commandName);
            if (definition != null) {
                return Optional.of(definition);
            }
        }
        return Optional.empty();
    }

    /**
     * The {@code confirmationTimeoutMs} for {@code command_issued}: the capability's positive
     * {@code default_timeout}, else the injected config fallback (Doc 07 §9). Always {@code > 0}.
     */
    private int resolveTimeoutMs(Optional<CommandDefinition> definition) {
        if (definition.isPresent()) {
            long capabilityMs = definition.get().defaultTimeout().toMillis();
            if (capabilityMs > 0) {
                return (int) Math.min(capabilityMs, Integer.MAX_VALUE);
            }
        }
        return (int) Math.min(defaultConfirmationTimeoutMs, Integer.MAX_VALUE);
    }

    /** Maps the device-model idempotency class onto the event-model {@link CommandIdempotency}. */
    private static CommandIdempotency mapIdempotency(IdempotencyClass deviceClass) {
        return switch (deviceClass) {
            case IDEMPOTENT -> CommandIdempotency.IDEMPOTENT;
            case NOT_IDEMPOTENT -> CommandIdempotency.NOT_IDEMPOTENT;
            case CONDITIONAL -> CommandIdempotency.CONDITIONAL;
        };
    }

    /** Serializes the parameter map to a non-blank JSON object string (AMD-95 floor "{}"). */
    private String serializeParameters(Map<String, Object> parameters) {
        String json = parameterSerializer.apply(parameters);
        return (json == null || json.isBlank()) ? EMPTY_PARAMETERS_JSON : json;
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
