/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import static com.homesynapse.automation.AutomationTestSupport.FIXED_CLOCK;
import static com.homesynapse.automation.AutomationTestSupport.FIXED_INSTANT;
import static com.homesynapse.automation.AutomationTestSupport.automationId;
import static com.homesynapse.automation.AutomationTestSupport.entityId;
import static com.homesynapse.automation.AutomationTestSupport.eventId;
import static com.homesynapse.automation.AutomationTestSupport.str;
import static com.homesynapse.automation.AutomationTestSupport.ulid;
import static org.assertj.core.api.Assertions.assertThat;

import com.homesynapse.event.AutomationActionCompletedEvent;
import com.homesynapse.event.AutomationActionStartedEvent;
import com.homesynapse.event.AutomationCompletedEvent;
import com.homesynapse.event.AutomationConditionEvaluatedEvent;
import com.homesynapse.event.AutomationConditionEvaluatedEvent.EvaluatedEntityState;
import com.homesynapse.event.AutomationTriggeredEvent;
import com.homesynapse.event.CausalContext;
import com.homesynapse.event.CommandConfirmationTimedOutEvent;
import com.homesynapse.event.CommandIdempotency;
import com.homesynapse.event.CommandIssuedEvent;
import com.homesynapse.event.CommandResultEvent;
import com.homesynapse.event.DomainEvent;
import com.homesynapse.event.EventDraft;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventId;
import com.homesynapse.event.EventOrigin;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.SequenceConflictException;
import com.homesynapse.event.StateChangedEvent;
import com.homesynapse.event.StateConfirmedEvent;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.event.test.InMemoryEventStore;
import com.homesynapse.platform.identity.AutomationId;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.Ulid;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link StandardExplanationService} — the log-derived causal-read projection.
 * Seeds a deterministic {@link InMemoryEventStore} (FIXED clock) with realistic run-lifecycle
 * and command-confirmation event chains, then asserts the assembled projection and the
 * pure-log outcome derivation (INV-SA-03).
 */
@DisplayName("StandardExplanationService")
final class StandardExplanationServiceTest {

    private InMemoryEventStore store;
    private AutomationTestSupport.MapAutomationRegistry registry;
    private ExplanationService service;

    /** Per-action confirmation shape to seed. */
    private enum ConfirmKind { CONFIRMED, UNCONFIRMED, FAILED, DISPATCHED }

    StandardExplanationServiceTest() {
    }

    @BeforeEach
    void setUp() {
        store = new InMemoryEventStore(FIXED_CLOCK);
        registry = new AutomationTestSupport.MapAutomationRegistry();
        service = ExplanationService.over(store, registry);
    }

    // ---- listRuns -----------------------------------------------------------

    @Test
    @DisplayName("listRuns returns terminal runs newest-first")
    void listRuns_returnsTerminalRunsNewestFirst() {
        RunId first = seedCompleted(automationId(), "COMPLETED", null);
        RunId second = seedCompleted(automationId(), "FAILED", "boom");
        RunId third = seedCompleted(automationId(), "COMPLETED", null);

        RunPage page = service.listRuns(Optional.empty(), 0, 50);

        assertThat(page.runs()).extracting(RunSummary::runId)
                .containsExactly(third, second, first);
        assertThat(page.runs()).extracting(s -> s.status().name())
                .containsExactly("COMPLETED", "FAILED", "COMPLETED");
        assertThat(page.runs().get(0).triggeredAt()).isNotNull();
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    @DisplayName("listRuns filters by automationId")
    void listRuns_filtersByAutomationId() {
        AutomationId wanted = automationId();
        AutomationId other = automationId();
        seedCompleted(wanted, "COMPLETED", null);
        seedCompleted(other, "COMPLETED", null);
        RunId wantedSecond = seedCompleted(wanted, "COMPLETED", null);

        RunPage page = service.listRuns(Optional.of(wanted), 0, 50);

        assertThat(page.runs()).hasSize(2);
        assertThat(page.runs()).allSatisfy(s -> assertThat(s.automationId()).isEqualTo(wanted));
        assertThat(page.runs().get(0).runId()).isEqualTo(wantedSecond);
    }

    @Test
    @DisplayName("listRuns returns an empty page (not an error) when no runs exist")
    void listRuns_emptyWhenNone() {
        RunPage page = service.listRuns(Optional.empty(), 0, 50);

        assertThat(page.runs()).isEmpty();
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    @DisplayName("listRuns paginates newest-first with an opaque position cursor")
    void listRuns_paginatesWithCursor() {
        RunId oldest = seedCompleted(automationId(), "COMPLETED", null);
        RunId middle = seedCompleted(automationId(), "COMPLETED", null);
        RunId newest = seedCompleted(automationId(), "COMPLETED", null);

        RunPage firstPage = service.listRuns(Optional.empty(), 0, 2);
        assertThat(firstPage.runs()).extracting(RunSummary::runId).containsExactly(newest, middle);
        assertThat(firstPage.hasMore()).isTrue();

        RunPage secondPage =
                service.listRuns(Optional.empty(), firstPage.nextCursorPosition(), 2);
        assertThat(secondPage.runs()).extracting(RunSummary::runId).containsExactly(oldest);
        assertThat(secondPage.hasMore()).isFalse();
    }

    // ---- explainRun: assembly -----------------------------------------------

    @Test
    @DisplayName("explainRun assembles trigger, conditions, actions, and outcome from the log")
    void explainRun_assemblesTriggerConditionsActionsOutcome() {
        AutomationId autoId = automationId();
        EntityId target = entityId();
        registry.add(definition(autoId, "My Automation", target));
        RunId runId = seedRun(autoId, target, "COMPLETED", null, ConfirmKind.CONFIRMED);

        RunExplanation explanation = service.explainRun(runId).orElseThrow();

        assertThat(explanation.runId()).isEqualTo(runId);
        assertThat(explanation.automationId()).isEqualTo(autoId);
        assertThat(explanation.automationName()).isEqualTo("My Automation");

        RunExplanation.TriggerView trigger = explanation.trigger();
        assertThat(trigger.type()).isEqualTo("StateTrigger");
        assertThat(trigger.subjectRef().type()).isEqualTo("entity");
        assertThat(trigger.subjectRef().id()).isEqualTo(target.toString());
        assertThat(trigger.matchedAt()).isEqualTo(FIXED_INSTANT);

        assertThat(explanation.conditions()).hasSize(1);
        RunExplanation.ConditionView condition = explanation.conditions().get(0);
        assertThat(condition.expression()).isEqualTo("StateCondition");
        assertThat(condition.evaluated()).isTrue();
        assertThat(condition.result()).isTrue();
        assertThat(condition.observedState()).singleElement().satisfies(o -> {
            assertThat(o.entityId()).isEqualTo(target.toString());
            assertThat(o.attribute()).isEqualTo("motion");
            assertThat(o.value()).isEqualTo("active");
        });

        assertThat(explanation.actions()).hasSize(1);
        RunExplanation.ActionView action = explanation.actions().get(0);
        assertThat(action.type()).isEqualTo("CommandAction");
        assertThat(action.targetRef().id()).isEqualTo(target.toString());
        assertThat(action.command()).isEqualTo("turn_on");
        assertThat(action.paramsJson()).isEqualTo("{\"level\":75}");
        assertThat(action.outcome()).isEqualTo(RunExplanation.ActionOutcome.CONFIRMED);

        RunExplanation.OutcomeView outcome = explanation.outcome();
        assertThat(outcome.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(outcome.durationMs()).isEqualTo(1234L);
        assertThat(outcome.actionCount()).isEqualTo(1);
        assertThat(outcome.commandCount()).isEqualTo(1);

        assertThat(explanation.cascade().depth()).isZero();
        assertThat(explanation.cascade().parentRunId()).isNull();
    }

    // ---- explainRun: outcome derivation (pure log) --------------------------

    @Test
    @DisplayName("explainRun derives CONFIRMED from a state_confirmed referencing the command")
    void explainRun_outcomeConfirmed() {
        AutomationId autoId = automationId();
        EntityId target = entityId();
        RunId runId = seedRun(autoId, target, "COMPLETED", null, ConfirmKind.CONFIRMED);

        assertThat(outcomeOf(runId)).isEqualTo(RunExplanation.ActionOutcome.CONFIRMED);
    }

    @Test
    @DisplayName("explainRun derives UNCONFIRMED from a command_confirmation_timed_out")
    void explainRun_outcomeUnconfirmed() {
        AutomationId autoId = automationId();
        EntityId target = entityId();
        RunId runId = seedRun(autoId, target, "COMPLETED", null, ConfirmKind.UNCONFIRMED);

        assertThat(outcomeOf(runId)).isEqualTo(RunExplanation.ActionOutcome.UNCONFIRMED);
    }

    @Test
    @DisplayName("explainRun derives FAILED from a command_result failure")
    void explainRun_outcomeFailed() {
        AutomationId autoId = automationId();
        EntityId target = entityId();
        RunId runId = seedRun(autoId, target, "COMPLETED", null, ConfirmKind.FAILED);

        RunExplanation.ActionView action = service.explainRun(runId).orElseThrow().actions().get(0);
        assertThat(action.outcome()).isEqualTo(RunExplanation.ActionOutcome.FAILED);
        assertThat(action.reason()).isEqualTo("device offline");
    }

    @Test
    @DisplayName("a confirmation-disabled command (no confirmation in the log) stays DISPATCHED")
    void explainRun_disabledModeStaysDispatched() {
        AutomationId autoId = automationId();
        EntityId target = entityId();
        // No state_confirmed and no timeout — exactly what a ConfirmationMode.DISABLED command
        // produces (the ledger never tracks it). It must NEVER render CONFIRMED (DP-A2).
        RunId runId = seedRun(autoId, target, "COMPLETED", null, ConfirmKind.DISPATCHED);

        assertThat(outcomeOf(runId)).isEqualTo(RunExplanation.ActionOutcome.DISPATCHED);
    }

    // ---- explainRun: status mapping carried by the projection ---------------

    @Test
    @DisplayName("explainRun carries the internal RunStatus (ABORTED / CONDITION_NOT_MET) + reason")
    void explainRun_statusMapping() {
        AutomationId autoId = automationId();
        EntityId target = entityId();
        RunId aborted = seedRun(autoId, target, "ABORTED", "restart_mode", ConfirmKind.DISPATCHED);
        RunId skipped =
                seedRun(automationId(), entityId(), "CONDITION_NOT_MET", null, ConfirmKind.DISPATCHED);

        RunExplanation.OutcomeView abortedOutcome = service.explainRun(aborted).orElseThrow().outcome();
        assertThat(abortedOutcome.status()).isEqualTo(RunStatus.ABORTED);
        assertThat(abortedOutcome.reason()).isEqualTo("restart_mode");

        RunExplanation.OutcomeView skippedOutcome = service.explainRun(skipped).orElseThrow().outcome();
        assertThat(skippedOutcome.status()).isEqualTo(RunStatus.CONDITION_NOT_MET);
    }

    // ---- explainRun: not found / filtering ----------------------------------

    @Test
    @DisplayName("explainRun returns empty for an unknown runId")
    void explainRun_unknownRunId_empty() {
        seedRun(automationId(), entityId(), "COMPLETED", null, ConfirmKind.CONFIRMED);

        assertThat(service.explainRun(new RunId(ulid()))).isEmpty();
    }

    @Test
    @DisplayName("explainRun filters to the requested run when a correlation holds multiple runs")
    void explainRun_multiRunCorrelation_filtersToRun() {
        AutomationId autoA = automationId();
        AutomationId autoB = automationId();
        EntityId targetA = entityId();
        EntityId targetB = entityId();

        // One triggering event shared by both runs (one correlation, two runs).
        EventEnvelope trig = publishRoot("state_changed", SubjectRef.entity(entityId()),
                new StateChangedEvent("motion", str("idle"), str("active"), eventId()));
        Ulid corr = trig.causalContext().correlationId();
        Ulid trigId = trig.eventId().value();

        RunId runA = seedRunOnCorrelation(autoA, targetA, corr, trigId, ConfirmKind.CONFIRMED);
        seedRunOnCorrelation(autoB, targetB, corr, trigId, ConfirmKind.FAILED);

        RunExplanation explanation = service.explainRun(runA).orElseThrow();

        assertThat(explanation.automationId()).isEqualTo(autoA);
        assertThat(explanation.actions()).singleElement().satisfies(a -> {
            assertThat(a.targetRef().id()).isEqualTo(targetA.toString());
            assertThat(a.outcome()).isEqualTo(RunExplanation.ActionOutcome.CONFIRMED);
        });
    }

    // ---- INV-SA-03: pure projection -----------------------------------------

    @Test
    @DisplayName("the projection writes nothing — latestPosition is unchanged after both reads")
    void projection_writesNothing() {
        RunId runId = seedRun(automationId(), entityId(), "COMPLETED", null, ConfirmKind.CONFIRMED);
        long before = store.latestPosition();

        service.listRuns(Optional.empty(), 0, 50);
        service.explainRun(runId);

        assertThat(store.latestPosition()).isEqualTo(before);
    }

    // ---- helpers ------------------------------------------------------------

    private RunExplanation.ActionOutcome outcomeOf(RunId runId) {
        return service.explainRun(runId).orElseThrow().actions().get(0).outcome();
    }

    /** Seeds only a terminal {@code automation_completed} marker (sufficient for listRuns). */
    private RunId seedCompleted(AutomationId autoId, String finalStatus, String failureReason) {
        RunId runId = new RunId(ulid());
        publishRoot(EventTypes.AUTOMATION_COMPLETED, SubjectRef.automation(autoId),
                new AutomationCompletedEvent(runId.value(), finalStatus, 1234L, 1, 1,
                        failureReason, null));
        return runId;
    }

    /** Seeds a full run chain on its own (root) correlation. */
    private RunId seedRun(AutomationId autoId, EntityId target, String finalStatus,
                          String abortReason, ConfirmKind kind) {
        EventEnvelope trig = publishRoot("state_changed", SubjectRef.entity(target),
                new StateChangedEvent("motion", str("idle"), str("active"), eventId()));
        return seedRunOnCorrelation(autoId, target, trig.causalContext().correlationId(),
                trig.eventId().value(), finalStatus, abortReason, kind, trig.eventId());
    }

    private RunId seedRunOnCorrelation(AutomationId autoId, EntityId target, Ulid corr, Ulid trigId,
                                       ConfirmKind kind) {
        return seedRunOnCorrelation(autoId, target, corr, trigId, "COMPLETED", null, kind,
                EventId.of(trigId));
    }

    private RunId seedRunOnCorrelation(AutomationId autoId, EntityId target, Ulid corr, Ulid trigId,
                                       String finalStatus, String abortReason, ConfirmKind kind,
                                       EventId triggeringEventId) {
        RunId runId = new RunId(ulid());
        publishDerived(EventTypes.AUTOMATION_TRIGGERED, SubjectRef.automation(autoId),
                new AutomationTriggeredEvent(runId.value(), triggeringEventId, List.of("t1"),
                        Map.of("action:0", Set.of(target)), "hash", 0), corr, trigId);
        publishDerived(EventTypes.AUTOMATION_CONDITION_EVALUATED, SubjectRef.automation(autoId),
                new AutomationConditionEvaluatedEvent(runId.value(), 0, "StateCondition", true,
                        List.of(new EvaluatedEntityState(target, "motion", "active",
                                FIXED_INSTANT, null))), corr, trigId);
        publishDerived(EventTypes.AUTOMATION_ACTION_STARTED, SubjectRef.automation(autoId),
                new AutomationActionStartedEvent(runId.value(), 0, "CommandAction", List.of(target)),
                corr, trigId);
        EventEnvelope cmd = publishDerived(EventTypes.COMMAND_ISSUED, SubjectRef.entity(target),
                new CommandIssuedEvent(target.value(), "turn_on", "{\"level\":75}", 5000,
                        CommandIdempotency.IDEMPOTENT), corr, trigId);
        switch (kind) {
            case CONFIRMED -> publishDerived(EventTypes.STATE_CONFIRMED, SubjectRef.entity(target),
                    new StateConfirmedEvent(cmd.eventId(), eventId(), "on", "true", "true", "exact"),
                    corr, cmd.eventId().value());
            case UNCONFIRMED -> publishDerived(EventTypes.COMMAND_CONFIRMATION_TIMED_OUT,
                    SubjectRef.entity(target),
                    new CommandConfirmationTimedOutEvent(cmd.eventId(), null),
                    corr, cmd.eventId().value());
            case FAILED -> publishDerived(EventTypes.COMMAND_RESULT, SubjectRef.entity(target),
                    new CommandResultEvent(target.value(), "turn_on", "rejected", "device offline"),
                    corr, cmd.eventId().value());
            case DISPATCHED -> {
                // no confirmation event — optimistic/confirmation-disabled or still in-flight
            }
        }
        publishDerived(EventTypes.AUTOMATION_ACTION_COMPLETED, SubjectRef.automation(autoId),
                new AutomationActionCompletedEvent(runId.value(), 0, "success", null), corr, trigId);
        publishDerived(EventTypes.AUTOMATION_COMPLETED, SubjectRef.automation(autoId),
                new AutomationCompletedEvent(runId.value(), finalStatus, 1234L, 1, 1, null,
                        abortReason), corr, trigId);
        return runId;
    }

    private static AutomationDefinition definition(AutomationId autoId, String name, EntityId entity) {
        return new AutomationDefinition(autoId, "auto-slug", name, null, true,
                ConcurrencyMode.SINGLE, 1, MaxExceededSeverity.INFO, 0,
                List.of(new StateTrigger(new DirectRefSelector(entity), "motion", "active", null,
                        "t1")),
                List.of(), List.of());
    }

    private EventEnvelope publishRoot(String eventType, SubjectRef subject, DomainEvent payload) {
        EventDraft draft = new EventDraft(eventType, 1, FIXED_INSTANT, subject,
                EventPriority.NORMAL, EventOrigin.AUTOMATION, payload, null, null);
        try {
            return store.publishRoot(draft);
        } catch (SequenceConflictException e) {
            throw new AssertionError("seed publish failed", e);
        }
    }

    private EventEnvelope publishDerived(String eventType, SubjectRef subject, DomainEvent payload,
                                         Ulid correlationId, Ulid causationId) {
        EventDraft draft = new EventDraft(eventType, 1, FIXED_INSTANT, subject,
                EventPriority.NORMAL, EventOrigin.AUTOMATION, payload, null, null);
        try {
            return store.publish(draft, CausalContext.chain(correlationId, causationId));
        } catch (SequenceConflictException e) {
            throw new AssertionError("seed publish failed", e);
        }
    }
}
