/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.homesynapse.event.AutomationActionCompletedEvent;
import com.homesynapse.event.AutomationActionStartedEvent;
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
 * {@link StandardActionExecutor} — the five Tier-1 actions with row 5/6 emission, the tally,
 * §6.2 fail-fast, and the Tier-2 reserved throw (DP-C/DP-D/DP-G).
 */
@DisplayName("StandardActionExecutor (M7.2a-2)")
class StandardActionExecutorTest {

    private final EntityId entity = AutomationTestSupport.entityId();
    private final Selector selector = new DirectRefSelector(entity);
    private final AutomationId automationId = AutomationTestSupport.automationId();

    private AutomationTestSupport.RecordingEventPublisher publisher;
    private AutomationTestSupport.RecordingDispatchService dispatchService;
    private AutomationTestSupport.StubStateQueryService stateQuery;
    private StandardActionExecutor executor;
    private RunContext context;
    private EventEnvelope trigger;

    @BeforeEach
    void setUp() {
        publisher = new AutomationTestSupport.RecordingEventPublisher();
        dispatchService = new AutomationTestSupport.RecordingDispatchService();
        AutomationTestSupport.FakeSelectorResolver resolver =
                new AutomationTestSupport.FakeSelectorResolver().bind(selector, java.util.Set.of(entity));
        stateQuery = new AutomationTestSupport.StubStateQueryService(
                AutomationTestSupport.snapshot(Map.of()));
        executor = new StandardActionExecutor(dispatchService, resolver,
                new StandardConditionEvaluator(resolver, AutomationTestSupport.FIXED_CLOCK),
                stateQuery, publisher, AutomationTestSupport.FIXED_CLOCK);
        context = new RunContext(new RunId(AutomationTestSupport.ulid()), automationId,
                AutomationTestSupport.eventId(), List.of(0), Map.of(), "hash",
                RunCausalChain.root(), 1L);
        trigger = AutomationTestSupport.stateChanged(entity, "on_off",
                AutomationTestSupport.str("off"), AutomationTestSupport.str("on"));
    }

    @Test
    @DisplayName("command action dispatches, counts, and emits row 5 + row 6(success)")
    void commandAction_dispatchesCountsEmits() {
        var action = new CommandAction(selector, "turn_on", Map.of(), UnavailablePolicy.SKIP);

        ActionExecutionResult result = executor.execute(List.of(action), context, trigger);

        assertThat(result.actionCount()).isEqualTo(1);
        assertThat(result.commandCount()).isEqualTo(1);
        assertThat(result.failed()).isFalse();
        assertThat(dispatchService.calls()).singleElement()
                .satisfies(call -> {
                    assertThat(call.targetRef()).isEqualTo(entity);
                    assertThat(call.commandName()).isEqualTo("turn_on");
                });
        List<EventEnvelope> started = publisher.ofType(EventTypes.AUTOMATION_ACTION_STARTED);
        assertThat(started).hasSize(1);
        AutomationActionStartedEvent startedPayload =
                (AutomationActionStartedEvent) started.get(0).payload();
        assertThat(startedPayload.actionType()).isEqualTo("CommandAction");
        assertThat(startedPayload.targetRefs()).containsExactly(entity);
        AutomationActionCompletedEvent completedPayload =
                (AutomationActionCompletedEvent) publisher
                        .ofType(EventTypes.AUTOMATION_ACTION_COMPLETED).get(0).payload();
        assertThat(completedPayload.outcome()).isEqualTo("success");
        assertThat(completedPayload.errorDetail()).isNull();
    }

    @Test
    @DisplayName("delay action suspends and completes")
    void delayAction_completes() {
        ActionExecutionResult result = executor.execute(
                List.of(new DelayAction(Duration.ofMillis(1))), context, trigger);

        assertThat(result.actionCount()).isEqualTo(1);
        assertThat(result.commandCount()).isZero();
        assertThat(result.failed()).isFalse();
    }

    @Test
    @DisplayName("wait-for completes when the condition already holds")
    void waitForAction_conditionMet_completes() {
        stateQuery.setSnapshot(snapshotWith("on", Availability.AVAILABLE));
        var action = new WaitForAction(new StateCondition(selector, "on_off", "on"),
                Duration.ofSeconds(10), null);

        ActionExecutionResult result = executor.execute(List.of(action), context, trigger);

        assertThat(result.failed()).isFalse();
        assertThat(result.actionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("wait-for completes (not an error) when the timeout elapses")
    void waitForAction_timeout_completes() {
        stateQuery.setSnapshot(snapshotWith("off", Availability.AVAILABLE));
        var action = new WaitForAction(new StateCondition(selector, "on_off", "on"),
                Duration.ZERO, null);

        ActionExecutionResult result = executor.execute(List.of(action), context, trigger);

        assertThat(result.failed()).isFalse();
        assertThat(((AutomationActionCompletedEvent) publisher
                .ofType(EventTypes.AUTOMATION_ACTION_COMPLETED).get(0).payload()).outcome())
                .isEqualTo("success");
    }

    @Test
    @DisplayName("condition-branch executes the taken branch's nested commands")
    void conditionBranch_takesThenBranch() {
        stateQuery.setSnapshot(snapshotWith("on", Availability.AVAILABLE));
        var branch = new ConditionBranchAction(new StateCondition(selector, "on_off", "on"),
                List.of(new CommandAction(selector, "turn_on", Map.of(), UnavailablePolicy.SKIP)),
                List.of());

        ActionExecutionResult result = executor.execute(List.of(branch), context, trigger);

        assertThat(result.actionCount()).isEqualTo(1);     // the branch is one top-level action
        assertThat(result.commandCount()).isEqualTo(1);    // its nested command dispatched
        assertThat(dispatchService.calls()).hasSize(1);
        assertThat(((AutomationActionStartedEvent) publisher
                .ofType(EventTypes.AUTOMATION_ACTION_STARTED).get(0).payload()).actionType())
                .isEqualTo("ConditionBranchAction");
    }

    @Test
    @DisplayName("emit-event publishes the user-defined event type")
    void emitEventAction_publishesCustomEvent() {
        var action = new EmitEventAction("my_event", Map.of("k", "v"));

        ActionExecutionResult result = executor.execute(List.of(action), context, trigger);

        assertThat(result.actionCount()).isEqualTo(1);
        assertThat(publisher.ofType("my_event")).hasSize(1);
    }

    @Test
    @DisplayName("a Tier-2 reserved action throws UnsupportedOperationException")
    void tier2Action_throwsUnsupported() {
        assertThatThrownBy(() -> executor.execute(List.of(new ActivateSceneAction()), context, trigger))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("Tier 2 action reserved");
    }

    @Test
    @DisplayName("§6.2 fail-fast: a failing action stops the sequence with outcome=error")
    void failFast_stopsSequenceWithError() {
        stateQuery.setSnapshot(snapshotWith("off", Availability.UNAVAILABLE));
        var failing = new CommandAction(selector, "turn_on", Map.of(), UnavailablePolicy.ERROR);
        var never = new CommandAction(selector, "turn_off", Map.of(), UnavailablePolicy.SKIP);

        ActionExecutionResult result = executor.execute(List.of(failing, never), context, trigger);

        assertThat(result.failed()).isTrue();
        assertThat(result.failureReason()).contains("unavailable");
        assertThat(result.actionCount()).isEqualTo(1);     // stopped at the first action
        assertThat(dispatchService.calls()).isEmpty();      // ERROR threw before any dispatch
        List<EventEnvelope> completed = publisher.ofType(EventTypes.AUTOMATION_ACTION_COMPLETED);
        assertThat(completed).hasSize(1);
        AutomationActionCompletedEvent payload =
                (AutomationActionCompletedEvent) completed.get(0).payload();
        assertThat(payload.outcome()).isEqualTo("error");
        assertThat(payload.errorDetail()).isNotNull();
    }

    private StateSnapshot snapshotWith(String onOff, Availability availability) {
        return AutomationTestSupport.snapshot(Map.of(entity,
                AutomationTestSupport.state(entity, availability,
                        Map.of("on_off", AutomationTestSupport.str(onOff)))));
    }
}
