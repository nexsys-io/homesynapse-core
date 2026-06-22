/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.homesynapse.event.AutomationConditionEvaluatedEvent;
import com.homesynapse.event.AutomationConditionEvaluatedEvent.EvaluatedEntityState;
import com.homesynapse.event.CausalContext;
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
import com.homesynapse.state.EntityState;
import com.homesynapse.state.StateSnapshot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Production {@link RunConditionGate}: evaluates an automation's top-level conditions against
 * the trigger-time {@link StateSnapshot} (AMD-03; Doc 07 §3.8) and publishes one
 * {@code automation_condition_evaluated} (AMD-92 row 4) per evaluated condition.
 *
 * <p>The implicit top-level conjunction short-circuits left-to-right: a condition that
 * evaluates false ends evaluation and the remaining conditions produce no row-4 event
 * (critical-review 2.3). Each row-4 carries the entity states the condition read (read-time
 * version tracking, critical-review 2.2) and publishes on the triggering event's
 * {@code CausalContext} (AMD-92 §2.4) with {@code actorRef = automationId} and inherited/null
 * {@code eventTime} — never {@code Instant.now()} (§4c).</p>
 *
 * <p>Pure with respect to FSM admission — it returns a value and publishes the diagnostic but
 * never mutates FSM state. Thread-safe and stateless apart from its injected collaborators;
 * publishes outside the FSM lock (the FSM invokes the gate before acquiring it).</p>
 */
public final class StandardRunConditionGate implements RunConditionGate {

    private static final Logger LOG = LoggerFactory.getLogger(StandardRunConditionGate.class);

    private static final int SCHEMA_VERSION = 1;

    private final ConditionEvaluator conditionEvaluator;
    private final SelectorResolver selectorResolver;
    private final EventPublisher publisher;

    /**
     * Constructs the gate over its injected collaborators.
     *
     * @param conditionEvaluator evaluates a condition against the snapshot, never {@code null}
     * @param selectorResolver   resolves a leaf condition's selector to the observed entities,
     *                           never {@code null}
     * @param publisher          the durable event publish surface, never {@code null}
     */
    public StandardRunConditionGate(ConditionEvaluator conditionEvaluator,
                                    SelectorResolver selectorResolver, EventPublisher publisher) {
        this.conditionEvaluator = Objects.requireNonNull(conditionEvaluator, "conditionEvaluator");
        this.selectorResolver = Objects.requireNonNull(selectorResolver, "selectorResolver");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
    }

    @Override
    public boolean conditionsHold(AutomationDefinition automation, RunContext context,
                                  EventEnvelope triggeringEvent, StateSnapshot snapshot) {
        Objects.requireNonNull(automation, "automation must not be null");
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(triggeringEvent, "triggeringEvent must not be null");
        Objects.requireNonNull(snapshot, "snapshot must not be null");

        List<ConditionDefinition> conditions = automation.conditions();
        for (int index = 0; index < conditions.size(); index++) {
            ConditionDefinition condition = conditions.get(index);
            boolean result = conditionEvaluator.evaluate(condition, snapshot);
            List<EvaluatedEntityState> observed = observedStates(condition, snapshot);
            publishConditionEvaluated(automation, context, index, condition, result, observed,
                    triggeringEvent);
            if (!result) {
                return false;   // short-circuit the implicit top-level AND
            }
        }
        return true;
    }

    private void publishConditionEvaluated(AutomationDefinition automation, RunContext context,
                                           int index, ConditionDefinition condition,
                                           boolean result, List<EvaluatedEntityState> observed,
                                           EventEnvelope triggeringEvent) {
        AutomationId automationId = automation.automationId();
        AutomationConditionEvaluatedEvent payload = new AutomationConditionEvaluatedEvent(
                context.runId().value(), index, condition.getClass().getSimpleName(), result,
                observed);
        EventDraft draft = new EventDraft(EventTypes.AUTOMATION_CONDITION_EVALUATED, SCHEMA_VERSION,
                triggeringEvent.eventTime(), SubjectRef.automation(automationId),
                EventPriority.DIAGNOSTIC, EventOrigin.AUTOMATION, payload, automationId.value(),
                null);
        try {
            publisher.publish(draft, CausalContext.chain(
                    triggeringEvent.causalContext().correlationId(),
                    triggeringEvent.eventId().value()));
        } catch (SequenceConflictException ex) {
            LOG.error("Failed to publish automation_condition_evaluated for automation {} "
                    + "condition {}: sequence conflict", automationId, index, ex);
        }
    }

    /** Walks the condition tree collecting the entity states the leaf conditions read. */
    private List<EvaluatedEntityState> observedStates(ConditionDefinition condition,
                                                      StateSnapshot snapshot) {
        List<EvaluatedEntityState> observed = new ArrayList<>();
        collectObserved(condition, snapshot, observed);
        return observed;
    }

    private void collectObserved(ConditionDefinition condition, StateSnapshot snapshot,
                                 List<EvaluatedEntityState> out) {
        switch (condition) {
            case StateCondition state -> observeAttribute(state.selector(), state.attribute(),
                    snapshot, out);
            case NumericCondition numeric -> observeAttribute(numeric.selector(),
                    numeric.attribute(), snapshot, out);
            case TimeCondition ignored -> {
                // time-based: no entity state observed
            }
            case AndCondition and -> and.conditions()
                    .forEach(child -> collectObserved(child, snapshot, out));
            case OrCondition or -> or.conditions()
                    .forEach(child -> collectObserved(child, snapshot, out));
            case NotCondition not -> collectObserved(not.condition(), snapshot, out);
            case ZoneCondition ignored -> {
                // Tier 2 reserved — rejected at load, never observed here
            }
        }
    }

    private void observeAttribute(Selector selector, String attribute, StateSnapshot snapshot,
                                  List<EvaluatedEntityState> out) {
        for (EntityId entityId : selectorResolver.resolve(selector)) {
            EntityState state = snapshot.states().get(entityId);
            if (state != null) {
                out.add(new EvaluatedEntityState(entityId, attribute,
                        AttributeValues.asString(state.attributes().get(attribute)),
                        state.lastChanged(), null));
            }
        }
    }
}
