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
import java.util.Set;
import java.util.function.Function;

import com.homesynapse.device.StandardCapabilities;
import com.homesynapse.event.AutomationActionCompletedEvent;
import com.homesynapse.event.AutomationActionStartedEvent;
import com.homesynapse.event.CommandIdempotency;
import com.homesynapse.event.CommandIssuedEvent;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventTypes;
import com.homesynapse.platform.identity.AutomationId;
import com.homesynapse.platform.identity.DeviceId;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.state.Availability;
import com.homesynapse.state.StateSnapshot;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link StandardActionExecutor} — the five Tier-1 actions with row 5/6 emission, the tally,
 * §6.2 fail-fast, the Tier-2 reserved throw (DP-C/DP-D/DP-G), and the M7.4a {@code command_issued}
 * producer (a command action emits {@code command_issued} per target — no in-process dispatch —
 * carrying the frozen 5 components with the capability-sourced timeout/idempotency).
 */
@DisplayName("StandardActionExecutor (M7.4a)")
class StandardActionExecutorTest {

    /** Distinct from the onOff capability's 5000 ms default_timeout, so the precedence is visible. */
    private static final long DEFAULT_CONFIRMATION_TIMEOUT_MS = 30_000L;

    /** Stand-in for the composition root's persistence-mapper-backed serializer (non-blank). */
    private static final Function<Map<String, Object>, String> PARAM_SERIALIZER =
            params -> params.isEmpty() ? "{}" : "{\"keys\":" + params.size() + "}";

    private final EntityId entity = AutomationTestSupport.entityId();
    private final DeviceId deviceId = AutomationTestSupport.deviceId();
    private final Selector selector = new DirectRefSelector(entity);
    private final AutomationId automationId = AutomationTestSupport.automationId();

    private AutomationTestSupport.RecordingEventPublisher publisher;
    private AutomationTestSupport.StubStateQueryService stateQuery;
    /** J3: the resolver as a field so a test can bind a second selector (S-3). */
    private AutomationTestSupport.FakeSelectorResolver resolver;
    private StandardActionExecutor executor;
    private RunContext context;
    private EventEnvelope trigger;

    @BeforeEach
    void setUp() {
        publisher = new AutomationTestSupport.RecordingEventPublisher();
        resolver = new AutomationTestSupport.FakeSelectorResolver().bind(selector, Set.of(entity));
        // The target entity carries the standard onOff capability (turn_on/turn_off:
        // default_timeout 5 s, IDEMPOTENT), so the producer resolves the capability-sourced
        // timeout + idempotency on command_issued.
        AutomationTestSupport.StubEntityRegistry entityRegistry =
                new AutomationTestSupport.StubEntityRegistry(List.of(
                        AutomationTestSupport.entityWith(entity, deviceId,
                                StandardCapabilities.onOff())));
        stateQuery = new AutomationTestSupport.StubStateQueryService(
                AutomationTestSupport.snapshot(Map.of()));
        executor = new StandardActionExecutor(entityRegistry, resolver,
                new StandardConditionEvaluator(resolver, AutomationTestSupport.FIXED_CLOCK),
                stateQuery, publisher, AutomationTestSupport.FIXED_CLOCK,
                DEFAULT_CONFIRMATION_TIMEOUT_MS, PARAM_SERIALIZER);
        context = new RunContext(new RunId(AutomationTestSupport.ulid()), automationId,
                AutomationTestSupport.eventId(), List.of(0), Map.of(), "hash",
                RunCausalChain.root(), 1L);
        trigger = AutomationTestSupport.stateChanged(entity, "on_off",
                AutomationTestSupport.str("off"), AutomationTestSupport.str("on"));
    }

    @Test
    @DisplayName("a command action emits exactly one command_issued (no in-process dispatch) + row 5/6")
    void commandAction_emitsCommandIssued_notInProcessDispatch() {
        var action = new CommandAction(selector, "turn_on", Map.of(), UnavailablePolicy.SKIP);

        ActionExecutionResult result = executor.execute(List.of(action), context, trigger);

        assertThat(result.actionCount()).isEqualTo(1);
        assertThat(result.commandCount()).isEqualTo(1);
        assertThat(result.failed()).isFalse();

        // The substrate-native hop: one command_issued, no command_dispatched/command_result
        // (those are the dispatch subscriber's, not the executor's).
        List<EventEnvelope> issued = publisher.ofType(EventTypes.COMMAND_ISSUED);
        assertThat(issued).hasSize(1);
        assertThat(publisher.ofType(EventTypes.COMMAND_DISPATCHED)).isEmpty();
        assertThat(publisher.ofType(EventTypes.COMMAND_RESULT)).isEmpty();

        EventEnvelope envelope = issued.get(0);
        CommandIssuedEvent payload = (CommandIssuedEvent) envelope.payload();
        assertThat(payload.targetEntityRef()).isEqualTo(entity.value());
        assertThat(payload.commandType()).isEqualTo("turn_on");
        assertThat(payload.parameters()).isEqualTo("{}");
        // command_issued is on the entity subject and threads the Run's causal chain
        // (correlation = the Run's, causation = the triggering event — Doc 07 §3.11.2).
        assertThat(envelope.subjectRef().id()).isEqualTo(entity.value());
        assertThat(envelope.causalContext().correlationId())
                .isEqualTo(trigger.causalContext().correlationId());
        assertThat(envelope.causalContext().causationId()).isEqualTo(trigger.eventId().value());

        // Row 5/6 still bracket the action.
        AutomationActionStartedEvent started = (AutomationActionStartedEvent) publisher
                .ofType(EventTypes.AUTOMATION_ACTION_STARTED).get(0).payload();
        assertThat(started.actionType()).isEqualTo("CommandAction");
        assertThat(started.targetRefs()).containsExactly(entity);
        AutomationActionCompletedEvent completed = (AutomationActionCompletedEvent) publisher
                .ofType(EventTypes.AUTOMATION_ACTION_COMPLETED).get(0).payload();
        assertThat(completed.outcome()).isEqualTo("success");
        assertThat(completed.errorDetail()).isNull();
    }

    @Test
    @DisplayName("command_issued carries the capability-sourced timeout and idempotency")
    void commandIssued_carriesResolvedTimeoutAndIdempotency() {
        var action = new CommandAction(selector, "turn_on", Map.of("level", 50),
                UnavailablePolicy.SKIP);

        executor.execute(List.of(action), context, trigger);

        CommandIssuedEvent payload = (CommandIssuedEvent) publisher
                .ofType(EventTypes.COMMAND_ISSUED).get(0).payload();
        // onOff turn_on: CommandDefinition.default_timeout = 5 s; IdempotencyClass.IDEMPOTENT.
        assertThat(payload.confirmationTimeoutMs()).isEqualTo(5_000);
        assertThat(payload.idempotencyClass()).isEqualTo(CommandIdempotency.IDEMPOTENT);
        // The injected serializer produced the parameters JSON (non-blank).
        assertThat(payload.parameters()).isEqualTo("{\"keys\":1}");
    }

    @Test
    @DisplayName("command_issued falls back to the config timeout + NOT_IDEMPOTENT when no "
            + "capability defines the command")
    void commandIssued_fallsBackToConfigTimeout_whenNoCapability() {
        var action = new CommandAction(selector, "frobnicate", Map.of(), UnavailablePolicy.SKIP);

        executor.execute(List.of(action), context, trigger);

        CommandIssuedEvent payload = (CommandIssuedEvent) publisher
                .ofType(EventTypes.COMMAND_ISSUED).get(0).payload();
        assertThat(payload.confirmationTimeoutMs()).isEqualTo((int) DEFAULT_CONFIRMATION_TIMEOUT_MS);
        assertThat(payload.idempotencyClass()).isEqualTo(CommandIdempotency.NOT_IDEMPOTENT);
    }

    @Test
    @DisplayName("N command actions emit N command_issued in issue order (AMD-31)")
    void multiCommandRun_emitsInActionOrder() {
        var first = new CommandAction(selector, "turn_on", Map.of(), UnavailablePolicy.SKIP);
        var second = new CommandAction(selector, "turn_off", Map.of(), UnavailablePolicy.SKIP);

        ActionExecutionResult result = executor.execute(List.of(first, second), context, trigger);

        assertThat(result.commandCount()).isEqualTo(2);
        assertThat(publisher.ofType(EventTypes.COMMAND_ISSUED))
                .extracting(e -> ((CommandIssuedEvent) e.payload()).commandType())
                .containsExactly("turn_on", "turn_off");
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
    @DisplayName("condition-branch emits the taken branch's nested command_issued")
    void conditionBranch_takesThenBranch() {
        stateQuery.setSnapshot(snapshotWith("on", Availability.AVAILABLE));
        var branch = new ConditionBranchAction(new StateCondition(selector, "on_off", "on"),
                List.of(new CommandAction(selector, "turn_on", Map.of(), UnavailablePolicy.SKIP)),
                List.of());

        ActionExecutionResult result = executor.execute(List.of(branch), context, trigger);

        assertThat(result.actionCount()).isEqualTo(1);     // the branch is one top-level action
        assertThat(result.commandCount()).isEqualTo(1);    // its nested command emitted
        assertThat(publisher.ofType(EventTypes.COMMAND_ISSUED)).hasSize(1);
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
        assertThat(publisher.ofType(EventTypes.COMMAND_ISSUED)).isEmpty();  // ERROR threw before emit
        List<EventEnvelope> completed = publisher.ofType(EventTypes.AUTOMATION_ACTION_COMPLETED);
        assertThat(completed).hasSize(1);
        AutomationActionCompletedEvent payload =
                (AutomationActionCompletedEvent) completed.get(0).payload();
        assertThat(payload.outcome()).isEqualTo("error");
        assertThat(payload.errorDetail()).isNotNull();
    }

    // ── J3 T-B (IR-95; SKIPVOC: count): a CommandAction that issues nothing completes `skipped` ──

    @Test
    @DisplayName("J3 S-1 (IR-95): one target UNAVAILABLE under SKIP — the action completes "
            + "outcome=skipped with errorDetail null; no command_issued; commandCount 0; "
            + "skippedUnavailable 1")
    void commandAction_everyTargetSkipped_completesSkipped() {
        stateQuery.setSnapshot(snapshotWith("off", Availability.UNAVAILABLE));
        var action = new CommandAction(selector, "turn_on", Map.of(), UnavailablePolicy.SKIP);

        ActionExecutionResult result = executor.execute(List.of(action), context, trigger);

        assertThat(result.failed()).isFalse();
        assertThat(result.actionCount()).isEqualTo(1);
        assertThat(result.commandCount()).isZero();
        assertThat(result.skippedUnavailable()).isEqualTo(1);
        assertThat(publisher.ofType(EventTypes.COMMAND_ISSUED)).isEmpty();
        AutomationActionCompletedEvent completed = onlyActionCompleted();
        assertThat(completed.outcome()).isEqualTo("skipped");
        assertThat(completed.errorDetail()).as("the reason lives in the counts and the log line")
                .isNull();
    }

    @Test
    @DisplayName("J3 S-2 (IR-95): a selector resolving to NO target — outcome=skipped; "
            + "resolved 0 and skippedUnavailable 0; commandCount 0")
    void commandAction_noTarget_completesSkipped() {
        // Unbound in the fake resolver → resolves to nothing (the selector named no entity).
        Selector none = new DirectRefSelector(AutomationTestSupport.entityId());
        var action = new CommandAction(none, "turn_on", Map.of(), UnavailablePolicy.SKIP);

        ActionExecutionResult result = executor.execute(List.of(action), context, trigger);

        assertThat(result.actionCount()).isEqualTo(1);
        assertThat(result.commandCount()).isZero();
        assertThat(result.skippedUnavailable()).isZero();
        assertThat(publisher.ofType(EventTypes.COMMAND_ISSUED)).isEmpty();
        AutomationActionCompletedEvent completed = onlyActionCompleted();
        assertThat(completed.outcome()).isEqualTo("skipped");
        assertThat(completed.errorDetail()).isNull();
    }

    @Test
    @DisplayName("J3 S-3 (IR-95): two targets, one available and one UNAVAILABLE under SKIP — "
            + "outcome=success, ONE command_issued to the available one, commandCount 1, "
            + "skippedUnavailable 1")
    void commandAction_oneOfTwoSkipped_completesSuccess_countsTheSkip() {
        EntityId dark = AutomationTestSupport.entityId();
        Selector pair = new DirectRefSelector(dark);
        resolver.bind(pair, Set.of(entity, dark));
        stateQuery.setSnapshot(AutomationTestSupport.snapshot(Map.of(
                entity, AutomationTestSupport.state(entity, Availability.AVAILABLE,
                        Map.of("on_off", AutomationTestSupport.str("off"))),
                dark, AutomationTestSupport.state(dark, Availability.UNAVAILABLE,
                        Map.of("on_off", AutomationTestSupport.str("off"))))));
        var action = new CommandAction(pair, "turn_on", Map.of(), UnavailablePolicy.SKIP);

        ActionExecutionResult result = executor.execute(List.of(action), context, trigger);

        assertThat(result.commandCount()).isEqualTo(1);
        assertThat(result.skippedUnavailable()).isEqualTo(1);
        List<EventEnvelope> issued = publisher.ofType(EventTypes.COMMAND_ISSUED);
        assertThat(issued).hasSize(1);
        assertThat(((CommandIssuedEvent) issued.get(0).payload()).targetEntityRef())
                .isEqualTo(entity.value());
        assertThat(onlyActionCompleted().outcome()).isEqualTo("success");
    }

    @Test
    @DisplayName("J3 S-4 (IR-95): DelayAction then CommandAction with an available target — "
            + "command_count means COMMANDS ISSUED (1), actionCount 2, skippedUnavailable 0; "
            + "both steps success")
    void delayThenCommand_pinsTheCommandCountMeaning() {
        stateQuery.setSnapshot(snapshotWith("off", Availability.AVAILABLE));
        var command = new CommandAction(selector, "turn_on", Map.of(), UnavailablePolicy.SKIP);

        ActionExecutionResult result = executor.execute(
                List.of(new DelayAction(Duration.ofMillis(1)), command), context, trigger);

        assertThat(result.actionCount()).isEqualTo(2);
        assertThat(result.commandCount()).isEqualTo(1);
        assertThat(result.skippedUnavailable()).isZero();
        assertThat(publisher.ofType(EventTypes.AUTOMATION_ACTION_COMPLETED))
                .extracting(e -> ((AutomationActionCompletedEvent) e.payload()).outcome())
                .isEqualTo(List.of("success", "success"));
    }

    private AutomationActionCompletedEvent onlyActionCompleted() {
        List<EventEnvelope> completed = publisher.ofType(EventTypes.AUTOMATION_ACTION_COMPLETED);
        assertThat(completed).hasSize(1);
        return (AutomationActionCompletedEvent) completed.get(0).payload();
    }

    private StateSnapshot snapshotWith(String onOff, Availability availability) {
        return AutomationTestSupport.snapshot(Map.of(entity,
                AutomationTestSupport.state(entity, availability,
                        Map.of("on_off", AutomationTestSupport.str(onOff)))));
    }
}
