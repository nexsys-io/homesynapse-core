/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.homesynapse.event.AutomationConditionEvaluatedEvent;
import com.homesynapse.event.AutomationConditionEvaluatedEvent.EvaluatedEntityState;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventTypes;
import com.homesynapse.platform.identity.AutomationId;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.state.Availability;
import com.homesynapse.state.StateSnapshot;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link StandardRunConditionGate} — evaluates top-level conditions over the trigger-time
 * snapshot, publishing the {@code automation_condition_evaluated} (AMD-92 row 4) diagnostic on
 * the triggering event's {@code CausalContext}, and short-circuits the implicit AND.
 */
@DisplayName("StandardRunConditionGate (M7.2a-2)")
class StandardRunConditionGateTest {

    private final EntityId entity = AutomationTestSupport.entityId();
    private final Selector selector = new DirectRefSelector(entity);
    private final AutomationId automationId = AutomationTestSupport.automationId();

    private AutomationTestSupport.RecordingEventPublisher publisher;
    private AutomationTestSupport.FakeSelectorResolver resolver;
    private StandardRunConditionGate gate;

    @BeforeEach
    void setUp() {
        publisher = new AutomationTestSupport.RecordingEventPublisher();
        resolver = new AutomationTestSupport.FakeSelectorResolver().bind(selector, Set.of(entity));
        gate = new StandardRunConditionGate(
                new StandardConditionEvaluator(resolver, AutomationTestSupport.FIXED_CLOCK),
                resolver, publisher);
    }

    @Test
    @DisplayName("conditions hold → true; row 4 carries the observed state + the causal context")
    void conditionsHold_emitRow4WithObservedStateAndCausalContext() {
        AutomationDefinition automation = automation(
                new StateCondition(selector, "on_off", "on"));
        StateSnapshot snapshot = snapshotWith("on");
        EventEnvelope trigger = AutomationTestSupport.stateChanged(entity, "on_off",
                AutomationTestSupport.str("off"), AutomationTestSupport.str("on"));
        RunContext context = context(snapshot);

        boolean held = gate.conditionsHold(automation, context, trigger, snapshot);

        assertThat(held).isTrue();
        List<EventEnvelope> row4 = publisher.ofType(EventTypes.AUTOMATION_CONDITION_EVALUATED);
        assertThat(row4).hasSize(1);
        AutomationConditionEvaluatedEvent payload =
                (AutomationConditionEvaluatedEvent) row4.get(0).payload();
        assertThat(payload.runId()).isEqualTo(context.runId().value());
        assertThat(payload.conditionIndex()).isZero();
        assertThat(payload.conditionType()).isEqualTo("StateCondition");
        assertThat(payload.result()).isTrue();
        assertThat(payload.evaluatedState()).extracting(EvaluatedEntityState::entityRef,
                        EvaluatedEntityState::attribute, EvaluatedEntityState::value)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(entity, "on_off", "on"));

        // Causal context: row 4 publishes on the triggering event's chain (AMD-92 §2.4).
        assertThat(row4.get(0).causalContext().correlationId())
                .isEqualTo(trigger.causalContext().correlationId());
        assertThat(row4.get(0).causalContext().causationId()).isEqualTo(trigger.eventId().value());
        assertThat(row4.get(0).actorRef()).isEqualTo(automationId.value());
    }

    @Test
    @DisplayName("conditions fail → false; row 4 records result=false")
    void conditionsFail_returnFalse() {
        AutomationDefinition automation = automation(
                new StateCondition(selector, "on_off", "on"));
        StateSnapshot snapshot = snapshotWith("off");
        EventEnvelope trigger = AutomationTestSupport.stateChanged(entity, "on_off",
                AutomationTestSupport.str("on"), AutomationTestSupport.str("off"));

        boolean held = gate.conditionsHold(automation, context(snapshot), trigger, snapshot);

        assertThat(held).isFalse();
        List<EventEnvelope> row4 = publisher.ofType(EventTypes.AUTOMATION_CONDITION_EVALUATED);
        assertThat(row4).hasSize(1);
        assertThat(((AutomationConditionEvaluatedEvent) row4.get(0).payload()).result()).isFalse();
    }

    @Test
    @DisplayName("the implicit top-level AND short-circuits: a false first condition stops evaluation")
    void shortCircuit_firstFalse_skipsRemaining() {
        AutomationDefinition automation = automation(
                new StateCondition(selector, "on_off", "on"),    // false against "off"
                new StateCondition(selector, "on_off", "off"));  // would be true — never evaluated
        StateSnapshot snapshot = snapshotWith("off");
        EventEnvelope trigger = AutomationTestSupport.stateChanged(entity, "on_off",
                AutomationTestSupport.str("on"), AutomationTestSupport.str("off"));

        boolean held = gate.conditionsHold(automation, context(snapshot), trigger, snapshot);

        assertThat(held).isFalse();
        assertThat(publisher.countOfType(EventTypes.AUTOMATION_CONDITION_EVALUATED)).isEqualTo(1);
    }

    private AutomationDefinition automation(ConditionDefinition... conditions) {
        return new AutomationDefinition(automationId, "auto", "auto", null, true,
                ConcurrencyMode.SINGLE, 1, MaxExceededSeverity.INFO, 0,
                List.of(new StateTrigger(selector, "on_off", "on", null, "t1")),
                List.of(conditions), List.of());
    }

    private StateSnapshot snapshotWith(String onOff) {
        return AutomationTestSupport.snapshot(Map.of(entity,
                AutomationTestSupport.state(entity, Availability.AVAILABLE,
                        Map.of("on_off", AutomationTestSupport.str(onOff)))));
    }

    private RunContext context(StateSnapshot snapshot) {
        return new RunContext(new RunId(AutomationTestSupport.ulid()), automationId,
                AutomationTestSupport.eventId(), List.of(0), Map.of(), "hash",
                RunCausalChain.root(), snapshot.viewPosition());
    }
}
