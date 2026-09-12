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
import com.homesynapse.event.StateReportedEvent;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.event.test.InMemoryEventStore;
import com.homesynapse.platform.identity.AutomationId;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.Ulid;
import com.homesynapse.value.IntValue;

import java.time.Instant;
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

    // ---- explainRun: raw outcome carry (DP-1, v1.1.2) -----------------------

    @Test
    @DisplayName("a superseded command is not FAILED — DISPATCHED with resultOutcome carried (CORE-P1)")
    void outcomeSuperseded_notFailed_carriesResultOutcome() {
        RunId runId = seedRunWithResult(automationId(), entityId(), "superseded",
                "superseded by a newer command on the same attribute; superseding command event 01KYD",
                false, false);

        RunExplanation.ActionView action = actionOf(runId);
        assertThat(action.outcome()).isNotEqualTo(RunExplanation.ActionOutcome.FAILED);
        assertThat(action.outcome()).isEqualTo(RunExplanation.ActionOutcome.DISPATCHED);
        assertThat(action.resultOutcome()).isEqualTo("superseded");
        assertThat(action.reason()).isNull();
    }

    @Test
    @DisplayName("a superseded command that also timed out derives UNCONFIRMED, resultOutcome carried")
    void outcomeSupersededThenTimeout_unconfirmed() {
        RunId runId = seedRunWithResult(automationId(), entityId(), "superseded",
                "superseded by a newer command on the same attribute", true, false);

        RunExplanation.ActionView action = actionOf(runId);
        assertThat(action.outcome()).isEqualTo(RunExplanation.ActionOutcome.UNCONFIRMED);
        assertThat(action.resultOutcome()).isEqualTo("superseded");
    }

    @Test
    @DisplayName("zigbee's honest-unconfirmed result derives UNCONFIRMED with the recorded reason verbatim")
    void outcomeHonestUnconfirmed_fromResult_reasonVerbatim() {
        RunId runId = seedRunWithResult(automationId(), entityId(), "unconfirmed",
                "DefaultResponse SUCCESS +90 ms, then no report, ever", false, false);

        RunExplanation.ActionView action = actionOf(runId);
        assertThat(action.outcome()).isEqualTo(RunExplanation.ActionOutcome.UNCONFIRMED);
        assertThat(action.reason()).isEqualTo("DefaultResponse SUCCESS +90 ms, then no report, ever");
        assertThat(action.resultOutcome()).isEqualTo("unconfirmed");
    }

    @Test
    @DisplayName("a rejected command stays FAILED — the failure set did not over-shrink (boundary)")
    void outcomeRejected_staysFailed() {
        RunId runId = seedRunWithResult(automationId(), entityId(), "rejected",
                "device offline", false, false);

        RunExplanation.ActionView action = actionOf(runId);
        assertThat(action.outcome()).isEqualTo(RunExplanation.ActionOutcome.FAILED);
        assertThat(action.reason()).isEqualTo("device offline");
        assertThat(action.resultOutcome()).isEqualTo("rejected");
    }

    @Test
    @DisplayName("an unknown adapter-specific outcome string stays failure-class (SD-7 pin)")
    void outcomeUnknownAdapterString_staysFailed() {
        RunId runId = seedRunWithResult(automationId(), entityId(), "zcl_weird_vendor_code",
                null, false, false);

        RunExplanation.ActionView action = actionOf(runId);
        assertThat(action.outcome()).isEqualTo(RunExplanation.ActionOutcome.FAILED);
        assertThat(action.resultOutcome()).isEqualTo("zcl_weird_vendor_code");
    }

    @Test
    @DisplayName("an acknowledged-only result stays DISPATCHED, resultOutcome carried")
    void outcomeAcknowledgedOnly_dispatched() {
        RunId runId = seedRunWithResult(automationId(), entityId(), "acknowledged",
                null, false, false);

        RunExplanation.ActionView action = actionOf(runId);
        assertThat(action.outcome()).isEqualTo(RunExplanation.ActionOutcome.DISPATCHED);
        assertThat(action.resultOutcome()).isEqualTo("acknowledged");
    }

    @Test
    @DisplayName("resultOutcome is a pure fact-carry — present even on a CONFIRMED action")
    void outcomeConfirmed_resultOutcomeCarried() {
        RunId runId = seedRunWithResult(automationId(), entityId(), "acknowledged",
                null, false, true);

        RunExplanation.ActionView action = actionOf(runId);
        assertThat(action.outcome()).isEqualTo(RunExplanation.ActionOutcome.CONFIRMED);
        assertThat(action.resultOutcome()).isEqualTo("acknowledged");
    }

    @Test
    @DisplayName("the five honest failure modes carry pairwise-distinct wire signatures")
    void explainRun_fiveFailureModesDistinct() {
        record Signature(String outcome, String resultOutcome, String reason) {
        }
        RunId timedOut =
                seedRun(automationId(), entityId(), "COMPLETED", null, ConfirmKind.UNCONFIRMED);
        RunId superseded = seedRunWithResult(automationId(), entityId(), "superseded",
                "superseded by a newer command on the same attribute", false, false);
        RunId honestUnconfirmed = seedRunWithResult(automationId(), entityId(), "unconfirmed",
                "DefaultResponse SUCCESS +90 ms, then no report, ever", false, false);
        RunId heldDispatched =
                seedRun(automationId(), entityId(), "COMPLETED", null, ConfirmKind.DISPATCHED);
        RunId settledFailed = seedRunWithResult(automationId(), entityId(), "rejected",
                "device offline", false, false);

        List<Signature> signatures = List.of(
                        timedOut, superseded, honestUnconfirmed, heldDispatched, settledFailed)
                .stream()
                .map(id -> {
                    RunExplanation.ActionView a = actionOf(id);
                    return new Signature(a.outcome().name(), a.resultOutcome(), a.reason());
                })
                .toList();

        assertThat(signatures.get(0))
                .isEqualTo(new Signature("UNCONFIRMED", null, "confirmation timed out"));
        assertThat(signatures.get(1))
                .isEqualTo(new Signature("DISPATCHED", "superseded", null));
        assertThat(signatures.get(2)).isEqualTo(new Signature("UNCONFIRMED", "unconfirmed",
                "DefaultResponse SUCCESS +90 ms, then no report, ever"));
        assertThat(signatures.get(3)).isEqualTo(new Signature("DISPATCHED", null, null));
        assertThat(signatures.get(4))
                .isEqualTo(new Signature("FAILED", "rejected", "device offline"));
        assertThat(Set.copyOf(signatures)).hasSize(5);
    }

    @Test
    @DisplayName("settled derives false only while DISPATCHED with no settling record (Q1b)")
    void actionSettled_provisionalOnlyWhileBareDispatchedOrAcked() {
        RunId bare = seedRun(automationId(), entityId(), "COMPLETED", null, ConfirmKind.DISPATCHED);
        RunId acked =
                seedRunWithResult(automationId(), entityId(), "acknowledged", null, false, false);
        RunId superseded = seedRunWithResult(automationId(), entityId(), "superseded",
                "superseded by a newer command", false, false);
        RunId confirmed =
                seedRun(automationId(), entityId(), "COMPLETED", null, ConfirmKind.CONFIRMED);
        RunId timedOut =
                seedRun(automationId(), entityId(), "COMPLETED", null, ConfirmKind.UNCONFIRMED);
        RunId failed = seedRun(automationId(), entityId(), "COMPLETED", null, ConfirmKind.FAILED);

        assertThat(actionOf(bare).settled()).isFalse();
        assertThat(actionOf(acked).settled()).isFalse();
        assertThat(actionOf(superseded).settled()).isTrue();
        assertThat(actionOf(confirmed).settled()).isTrue();
        assertThat(actionOf(timedOut).settled()).isTrue();
        assertThat(actionOf(failed).settled()).isTrue();
    }

    // ---- listRuns: triggeredAt derivation (DP-3, v1.1.2) --------------------

    @Test
    @DisplayName("triggeredAt equals the terminal envelope's inherited eventTime exactly (DP-G)")
    void listRuns_triggeredAtEqualsEventTime() {
        seedCompletedWithDuration(automationId(), 34204L);

        RunPage page = service.listRuns(Optional.empty(), 0, 50);

        assertThat(page.runs()).singleElement().satisfies(s ->
                assertThat(s.triggeredAt()).isEqualTo(FIXED_INSTANT));
    }

    @Test
    @DisplayName("with a null eventTime, triggeredAt falls back to ingestTime minus the duration")
    void listRuns_triggeredAt_ingestFallback() {
        seedCompletedNullEventTime(automationId(), 34204L);

        RunPage page = service.listRuns(Optional.empty(), 0, 50);

        // InMemoryEventStore stamps ingestTime = clock.instant() (FIXED_CLOCK) at append.
        assertThat(page.runs()).singleElement().satisfies(s ->
                assertThat(s.triggeredAt()).isEqualTo(FIXED_INSTANT.minusMillis(34204L)));
    }

    @Test
    @DisplayName("runs[].triggeredAt equals the causal-chain trigger.matchedAt (the alignment law)")
    void triggeredAt_equalsMatchedAt_regression() {
        AutomationId autoId = automationId();
        RunId runId = seedRun(autoId, entityId(), "COMPLETED", null, ConfirmKind.CONFIRMED);

        Instant matchedAt = service.explainRun(runId).orElseThrow().trigger().matchedAt();
        RunPage page = service.listRuns(Optional.of(autoId), 0, 50);

        assertThat(page.runs()).singleElement().satisfies(s ->
                assertThat(s.triggeredAt()).isEqualTo(matchedAt));
    }

    // ---- v1.1.4 (EXPLAIN-114a): firingValue / settledAt / confirmedAt / definitionKey ----

    @Test
    @DisplayName("firingValue is the in-correlation state_changed newValue in the string dialect (T1)")
    void firingValue_fromInCorrelationStateChanged_isTheNewValueString() {
        AutomationId autoId = automationId();
        EntityId target = entityId();
        // seedRun's triggering state_changed (newValue str("active")) rides the run's correlation.
        RunId runId = seedRun(autoId, target, "COMPLETED", null, ConfirmKind.CONFIRMED);

        RunExplanation.TriggerView trigger = service.explainRun(runId).orElseThrow().trigger();

        assertThat(trigger.subjectRef()).isNotNull();
        assertThat(trigger.firingValue()).isEqualTo("active");
    }

    @Test
    @DisplayName("a numeric newValue renders through the module's one string dialect, never a record toString (T1b)")
    void firingValue_numericNewValue_rendersTheCanonicalString() {
        EntityId target = entityId();
        RunId runId = seedRunTriggeredBy(automationId(), target, EventTypes.STATE_CHANGED,
                SubjectRef.entity(target),
                new StateChangedEvent("brightness", new IntValue(10), new IntValue(72), eventId()),
                ConfirmKind.CONFIRMED);

        assertThat(service.explainRun(runId).orElseThrow().trigger().firingValue()).isEqualTo("72");
    }

    @Test
    @DisplayName("firingValue is the in-correlation state_reported value as recorded (T2)")
    void firingValue_fromInCorrelationStateReported_isTheReportedValue() {
        EntityId target = entityId();
        RunId runId = seedRunTriggeredBy(automationId(), target, EventTypes.STATE_REPORTED,
                SubjectRef.entity(target),
                new StateReportedEvent("temperature", "21.5", "C", "0x0866", null),
                ConfirmKind.CONFIRMED);

        assertThat(service.explainRun(runId).orElseThrow().trigger().firingValue()).isEqualTo("21.5");
    }

    @Test
    @DisplayName("firingValue is null when the triggering event is not in the run's correlation (T3)")
    void firingValue_nullWhenTriggeringEventOutsideTheCorrelation() {
        AutomationId autoId = automationId();
        EntityId target = entityId();
        // The triggering state_changed lives on its OWN root correlation ...
        EventEnvelope trig = publishRoot(EventTypes.STATE_CHANGED, SubjectRef.entity(target),
                new StateChangedEvent("motion", str("idle"), str("active"), eventId()));
        // ... while the run chain rides a different correlation that names it by id only. An
        // engine run always inherits the triggering envelope's correlation (StandardRunManager
        // :218), so this shape arises only when that envelope is no longer retained — the
        // correlation read then cannot see it (the F3 class).
        EventEnvelope other = publishRoot(EventTypes.STATE_CHANGED, SubjectRef.entity(entityId()),
                new StateChangedEvent("motion", str("idle"), str("active"), eventId()));
        RunId runId = seedRunOnCorrelation(autoId, target, other.causalContext().correlationId(),
                other.eventId().value(), "COMPLETED", null, ConfirmKind.CONFIRMED, trig.eventId());

        RunExplanation.TriggerView trigger = service.explainRun(runId).orElseThrow().trigger();

        assertThat(trigger.subjectRef()).isNull();
        assertThat(trigger.firingValue()).isNull();
    }

    @Test
    @DisplayName("firingValue is null for an in-correlation triggering event that is not a state event (T3b)")
    void firingValue_nullForNonStateTriggeringPayload() {
        EntityId target = entityId();
        RunId runId = seedRunTriggeredBy(automationId(), target, EventTypes.COMMAND_ISSUED,
                SubjectRef.entity(target),
                new CommandIssuedEvent(target.value(), "turn_on", "{}", 5000,
                        CommandIdempotency.IDEMPOTENT),
                ConfirmKind.CONFIRMED);

        RunExplanation.TriggerView trigger = service.explainRun(runId).orElseThrow().trigger();

        assertThat(trigger.subjectRef()).isNotNull();
        assertThat(trigger.firingValue()).isNull();
    }

    @Test
    @DisplayName("CONFIRMED: settledAt and confirmedAt both equal the state_confirmed instant (T4)")
    void settledAt_confirmedAt_forConfirmed_equalTheStateConfirmedInstant() {
        Instant confirmedAt = FIXED_INSTANT.plusSeconds(3);
        RunId runId = seedRunSettledAt(automationId(), entityId(), ConfirmKind.CONFIRMED, confirmedAt);

        RunExplanation.ActionView action = actionOf(runId);

        assertThat(action.outcome()).isEqualTo(RunExplanation.ActionOutcome.CONFIRMED);
        assertThat(action.settledAt()).isEqualTo(confirmedAt);
        assertThat(action.confirmedAt()).isEqualTo(confirmedAt);
    }

    @Test
    @DisplayName("a classifying command_result sets settledAt to its instant; confirmedAt stays null (T5)")
    void settledAt_forClassifyingResult_isTheResultInstant_confirmedAtNull() {
        Instant unconfirmedAt = FIXED_INSTANT.plusSeconds(5);
        Instant failedAt = FIXED_INSTANT.plusSeconds(4);
        RunId unconfirmed = seedRunWithResultAt(automationId(), entityId(), "unconfirmed",
                "DefaultResponse SUCCESS +90 ms, then no report, ever", unconfirmedAt);
        RunId failed = seedRunWithResultAt(automationId(), entityId(), "rejected", "device offline",
                failedAt);

        assertThat(actionOf(unconfirmed).outcome()).isEqualTo(RunExplanation.ActionOutcome.UNCONFIRMED);
        assertThat(actionOf(unconfirmed).settledAt()).isEqualTo(unconfirmedAt);
        assertThat(actionOf(unconfirmed).confirmedAt()).isNull();
        assertThat(actionOf(failed).outcome()).isEqualTo(RunExplanation.ActionOutcome.FAILED);
        assertThat(actionOf(failed).settledAt()).isEqualTo(failedAt);
        assertThat(actionOf(failed).confirmedAt()).isNull();
    }

    @Test
    @DisplayName("a confirmation timeout sets settledAt to its instant; ingestTime when its eventTime is null (T6)")
    void settledAt_forTimeout_isTheTimeoutInstant_ingestFallbackWhenEventTimeNull() {
        Instant timedOutAt = FIXED_INSTANT.plusSeconds(9);
        RunId timed = seedRunSettledAt(automationId(), entityId(), ConfirmKind.UNCONFIRMED, timedOutAt);
        RunId noEventTime = seedRunSettledAt(automationId(), entityId(), ConfirmKind.UNCONFIRMED, null);

        assertThat(actionOf(timed).outcome()).isEqualTo(RunExplanation.ActionOutcome.UNCONFIRMED);
        assertThat(actionOf(timed).settledAt()).isEqualTo(timedOutAt);
        assertThat(actionOf(timed).confirmedAt()).isNull();
        // InMemoryEventStore stamps ingestTime = clock.instant() (FIXED_CLOCK) at append.
        assertThat(actionOf(noEventTime).settledAt()).isEqualTo(FIXED_INSTANT);
    }

    @Test
    @DisplayName("DISPATCHED: bare and acknowledged carry no settling instant; a superseded one settles at its result's instant (T7, R3)")
    void settledAt_confirmedAt_forDispatched_followSettled() {
        RunId bare = seedRun(automationId(), entityId(), "COMPLETED", null, ConfirmKind.DISPATCHED);
        RunId acked = seedRunWithResultAt(automationId(), entityId(), "acknowledged", null,
                FIXED_INSTANT.plusSeconds(1));
        Instant supersededAt = FIXED_INSTANT.plusSeconds(2);
        RunId superseded = seedRunWithResultAt(automationId(), entityId(), "superseded",
                "superseded by a newer command", supersededAt);

        for (RunId runId : List.of(bare, acked)) {
            RunExplanation.ActionView action = actionOf(runId);
            assertThat(action.outcome()).isEqualTo(RunExplanation.ActionOutcome.DISPATCHED);
            assertThat(action.settled()).as("settled").isFalse();
            assertThat(action.settledAt()).as("settledAt").isNull();
            assertThat(action.confirmedAt()).as("confirmedAt").isNull();
        }
        // The superseded command_result IS the settling record (the ledger dropped the command;
        // nothing further arrives): settled == true and settledAt is that envelope's instant.
        RunExplanation.ActionView action = actionOf(superseded);
        assertThat(action.outcome()).isEqualTo(RunExplanation.ActionOutcome.DISPATCHED);
        assertThat(action.settled()).isTrue();
        assertThat(action.settledAt()).isEqualTo(supersededAt);
        assertThat(action.confirmedAt()).isNull();
    }

    @Test
    @DisplayName("settledAt != null exactly when settled — the v1.1.4 invariant over every fixture (T7c, R3)")
    void settledAt_presentIffSettled_overEveryFixture() {
        String supersededReason = "superseded by a newer command";
        String honestReason = "DefaultResponse SUCCESS +90 ms, then no report, ever";
        List<RunId> fixtures = List.of(
                seedRun(automationId(), entityId(), "COMPLETED", null, ConfirmKind.CONFIRMED),
                seedRun(automationId(), entityId(), "COMPLETED", null, ConfirmKind.UNCONFIRMED),
                seedRun(automationId(), entityId(), "COMPLETED", null, ConfirmKind.FAILED),
                seedRun(automationId(), entityId(), "COMPLETED", null, ConfirmKind.DISPATCHED),
                seedRunWithResult(automationId(), entityId(), "superseded", supersededReason, false, false),
                seedRunWithResult(automationId(), entityId(), "superseded", supersededReason, true, false),
                seedRunWithResult(automationId(), entityId(), "unconfirmed", honestReason, false, false),
                seedRunWithResult(automationId(), entityId(), "rejected", "device offline", false, false),
                seedRunWithResult(automationId(), entityId(), "zcl_weird_vendor_code", null, false, false),
                seedRunWithResult(automationId(), entityId(), "acknowledged", null, false, false),
                seedRunWithResult(automationId(), entityId(), "acknowledged", null, false, true),
                seedRunSettledAt(automationId(), entityId(), ConfirmKind.CONFIRMED,
                        FIXED_INSTANT.plusSeconds(3)),
                seedRunSettledAt(automationId(), entityId(), ConfirmKind.UNCONFIRMED, null),
                seedRunSettledAt(automationId(), entityId(), ConfirmKind.FAILED,
                        FIXED_INSTANT.plusSeconds(4)),
                seedRunWithResultAt(automationId(), entityId(), "acknowledged", null,
                        FIXED_INSTANT.plusSeconds(1)),
                seedRunWithResultAt(automationId(), entityId(), "superseded", supersededReason,
                        FIXED_INSTANT.plusSeconds(2)),
                seedSkippedRun(automationId(), entityId(), FIXED_INSTANT.plusSeconds(2)));

        for (RunId runId : fixtures) {
            RunExplanation.ActionView action = actionOf(runId);
            assertThat(action.settledAt() != null)
                    .as("settledAt present iff settled: %s / %s", action.outcome(),
                            action.resultOutcome())
                    .isEqualTo(action.settled());
        }
    }

    @Test
    @DisplayName("a SKIPPED command action settles at its automation_action_completed instant (T7b)")
    void settledAt_forSkippedAction_isTheActionCompletedInstant() {
        Instant skippedAt = FIXED_INSTANT.plusSeconds(2);
        RunId runId = seedSkippedRun(automationId(), entityId(), skippedAt);

        RunExplanation.ActionView action = actionOf(runId);

        assertThat(action.outcome()).isEqualTo(RunExplanation.ActionOutcome.SKIPPED);
        assertThat(action.settledAt()).isEqualTo(skippedAt);
        assertThat(action.confirmedAt()).isNull();
    }

    @Test
    @DisplayName("definitionKey is the run's automation_triggered definitionHash (T8)")
    void definitionKey_equalsTheTriggeredEventsDefinitionHash() {
        RunId runId = seedRun(automationId(), entityId(), "COMPLETED", null, ConfirmKind.CONFIRMED);

        // seedRun stamps the fixture hash "hash" on automation_triggered.
        assertThat(service.explainRun(runId).orElseThrow().definitionKey()).isEqualTo("hash");
    }

    // ---- INV-SA-03: pure projection -----------------------------------------

    @Test
    @DisplayName("the projection writes nothing — latestPosition is unchanged after both reads")
    void projection_writesNothing() {
        RunId runId = seedRun(automationId(), entityId(), "COMPLETED", null, ConfirmKind.CONFIRMED);
        RunId superseded = seedRunWithResult(automationId(), entityId(), "superseded",
                "superseded by a newer command", false, false);
        long before = store.latestPosition();

        service.listRuns(Optional.empty(), 0, 50);
        service.explainRun(runId);
        service.explainRun(superseded);

        assertThat(store.latestPosition()).isEqualTo(before);
    }

    // ---- helpers ------------------------------------------------------------

    private RunExplanation.ActionOutcome outcomeOf(RunId runId) {
        return service.explainRun(runId).orElseThrow().actions().get(0).outcome();
    }

    private RunExplanation.ActionView actionOf(RunId runId) {
        return service.explainRun(runId).orElseThrow().actions().get(0);
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

    /**
     * Seeds a full run chain whose command carries a {@code command_result} of the given outcome
     * (the DP-1 fixture), optionally followed by a confirmation timeout and/or a
     * {@code state_confirmed} for the same command.
     */
    private RunId seedRunWithResult(AutomationId autoId, EntityId target, String resultOutcome,
                                    String failureReason, boolean alsoTimeout,
                                    boolean alsoConfirmed) {
        EventEnvelope trig = publishRoot("state_changed", SubjectRef.entity(target),
                new StateChangedEvent("motion", str("idle"), str("active"), eventId()));
        Ulid corr = trig.causalContext().correlationId();
        Ulid trigId = trig.eventId().value();
        RunId runId = new RunId(ulid());
        publishDerived(EventTypes.AUTOMATION_TRIGGERED, SubjectRef.automation(autoId),
                new AutomationTriggeredEvent(runId.value(), trig.eventId(), List.of("t1"),
                        Map.of("action:0", Set.of(target)), "hash", 0), corr, trigId);
        publishDerived(EventTypes.AUTOMATION_ACTION_STARTED, SubjectRef.automation(autoId),
                new AutomationActionStartedEvent(runId.value(), 0, "CommandAction", List.of(target)),
                corr, trigId);
        EventEnvelope cmd = publishDerived(EventTypes.COMMAND_ISSUED, SubjectRef.entity(target),
                new CommandIssuedEvent(target.value(), "set_color_temp", "{\"mireds\":220}", 5000,
                        CommandIdempotency.IDEMPOTENT), corr, trigId);
        publishDerived(EventTypes.COMMAND_RESULT, SubjectRef.entity(target),
                new CommandResultEvent(target.value(), "set_color_temp", resultOutcome,
                        failureReason), corr, cmd.eventId().value());
        if (alsoTimeout) {
            publishDerived(EventTypes.COMMAND_CONFIRMATION_TIMED_OUT, SubjectRef.entity(target),
                    new CommandConfirmationTimedOutEvent(cmd.eventId(), null),
                    corr, cmd.eventId().value());
        }
        if (alsoConfirmed) {
            publishDerived(EventTypes.STATE_CONFIRMED, SubjectRef.entity(target),
                    new StateConfirmedEvent(cmd.eventId(), eventId(), "on", "true", "true", "exact"),
                    corr, cmd.eventId().value());
        }
        publishDerived(EventTypes.AUTOMATION_ACTION_COMPLETED, SubjectRef.automation(autoId),
                new AutomationActionCompletedEvent(runId.value(), 0, "success", null), corr, trigId);
        publishDerived(EventTypes.AUTOMATION_COMPLETED, SubjectRef.automation(autoId),
                new AutomationCompletedEvent(runId.value(), "COMPLETED", 1234L, 1, 1, null, null),
                corr, trigId);
        return runId;
    }

    /** Seeds a terminal marker with an explicit duration (the DP-3 fixture; eventTime present). */
    private RunId seedCompletedWithDuration(AutomationId autoId, long durationMs) {
        RunId runId = new RunId(ulid());
        publishRoot(EventTypes.AUTOMATION_COMPLETED, SubjectRef.automation(autoId),
                new AutomationCompletedEvent(runId.value(), "COMPLETED", durationMs, 1, 1,
                        null, null));
        return runId;
    }

    /**
     * Seeds a terminal marker whose envelope {@code eventTime} is null (the ingest-fallback
     * fixture — the {@link EventDraft} accepts a null eventTime, as CMD-API roots publish live).
     */
    private RunId seedCompletedNullEventTime(AutomationId autoId, long durationMs) {
        RunId runId = new RunId(ulid());
        EventDraft draft = new EventDraft(EventTypes.AUTOMATION_COMPLETED, 1, null,
                SubjectRef.automation(autoId), EventPriority.NORMAL, EventOrigin.AUTOMATION,
                new AutomationCompletedEvent(runId.value(), "COMPLETED", durationMs, 1, 1,
                        null, null), null, null);
        try {
            store.publishRoot(draft);
        } catch (SequenceConflictException e) {
            throw new AssertionError("seed publish failed", e);
        }
        return runId;
    }

    /** Seeds a run whose triggering event has the given type/subject/payload on the run's correlation. */
    private RunId seedRunTriggeredBy(AutomationId autoId, EntityId target, String triggerEventType,
                                     SubjectRef triggerSubject, DomainEvent triggerPayload,
                                     ConfirmKind kind) {
        EventEnvelope trig = publishRoot(triggerEventType, triggerSubject, triggerPayload);
        return seedRunOnCorrelation(autoId, target, trig.causalContext().correlationId(),
                trig.eventId().value(), kind);
    }

    /**
     * Seeds a full run chain whose classifying confirmation event carries an explicit envelope
     * {@code eventTime} ({@code null} allowed — the ingest-fallback fixture); every other event
     * stays at {@link #FIXED_INSTANT} so the settling instant is discriminable.
     */
    private RunId seedRunSettledAt(AutomationId autoId, EntityId target, ConfirmKind kind,
                                   Instant classifyingTime) {
        EventEnvelope trig = publishRoot("state_changed", SubjectRef.entity(target),
                new StateChangedEvent("motion", str("idle"), str("active"), eventId()));
        Ulid corr = trig.causalContext().correlationId();
        Ulid trigId = trig.eventId().value();
        RunId runId = new RunId(ulid());
        publishDerived(EventTypes.AUTOMATION_TRIGGERED, SubjectRef.automation(autoId),
                new AutomationTriggeredEvent(runId.value(), trig.eventId(), List.of("t1"),
                        Map.of("action:0", Set.of(target)), "hash", 0), corr, trigId);
        publishDerived(EventTypes.AUTOMATION_ACTION_STARTED, SubjectRef.automation(autoId),
                new AutomationActionStartedEvent(runId.value(), 0, "CommandAction", List.of(target)),
                corr, trigId);
        EventEnvelope cmd = publishDerived(EventTypes.COMMAND_ISSUED, SubjectRef.entity(target),
                new CommandIssuedEvent(target.value(), "turn_on", "{\"level\":75}", 5000,
                        CommandIdempotency.IDEMPOTENT), corr, trigId);
        switch (kind) {
            case CONFIRMED -> publishDerivedAt(EventTypes.STATE_CONFIRMED, SubjectRef.entity(target),
                    new StateConfirmedEvent(cmd.eventId(), eventId(), "on", "true", "true", "exact"),
                    corr, cmd.eventId().value(), classifyingTime);
            case UNCONFIRMED -> publishDerivedAt(EventTypes.COMMAND_CONFIRMATION_TIMED_OUT,
                    SubjectRef.entity(target),
                    new CommandConfirmationTimedOutEvent(cmd.eventId(), null),
                    corr, cmd.eventId().value(), classifyingTime);
            case FAILED -> publishDerivedAt(EventTypes.COMMAND_RESULT, SubjectRef.entity(target),
                    new CommandResultEvent(target.value(), "turn_on", "rejected", "device offline"),
                    corr, cmd.eventId().value(), classifyingTime);
            case DISPATCHED -> {
                // no classifying event
            }
        }
        publishDerived(EventTypes.AUTOMATION_ACTION_COMPLETED, SubjectRef.automation(autoId),
                new AutomationActionCompletedEvent(runId.value(), 0, "success", null), corr, trigId);
        publishDerived(EventTypes.AUTOMATION_COMPLETED, SubjectRef.automation(autoId),
                new AutomationCompletedEvent(runId.value(), "COMPLETED", 1234L, 1, 1, null, null),
                corr, trigId);
        return runId;
    }

    /** Seeds a run chain whose single command_result carries an explicit envelope eventTime. */
    private RunId seedRunWithResultAt(AutomationId autoId, EntityId target, String resultOutcome,
                                      String failureReason, Instant resultTime) {
        EventEnvelope trig = publishRoot("state_changed", SubjectRef.entity(target),
                new StateChangedEvent("motion", str("idle"), str("active"), eventId()));
        Ulid corr = trig.causalContext().correlationId();
        Ulid trigId = trig.eventId().value();
        RunId runId = new RunId(ulid());
        publishDerived(EventTypes.AUTOMATION_TRIGGERED, SubjectRef.automation(autoId),
                new AutomationTriggeredEvent(runId.value(), trig.eventId(), List.of("t1"),
                        Map.of("action:0", Set.of(target)), "hash", 0), corr, trigId);
        publishDerived(EventTypes.AUTOMATION_ACTION_STARTED, SubjectRef.automation(autoId),
                new AutomationActionStartedEvent(runId.value(), 0, "CommandAction", List.of(target)),
                corr, trigId);
        EventEnvelope cmd = publishDerived(EventTypes.COMMAND_ISSUED, SubjectRef.entity(target),
                new CommandIssuedEvent(target.value(), "set_color_temp", "{\"mireds\":220}", 5000,
                        CommandIdempotency.IDEMPOTENT), corr, trigId);
        publishDerivedAt(EventTypes.COMMAND_RESULT, SubjectRef.entity(target),
                new CommandResultEvent(target.value(), "set_color_temp", resultOutcome,
                        failureReason), corr, cmd.eventId().value(), resultTime);
        publishDerived(EventTypes.AUTOMATION_ACTION_COMPLETED, SubjectRef.automation(autoId),
                new AutomationActionCompletedEvent(runId.value(), 0, "success", null), corr, trigId);
        publishDerived(EventTypes.AUTOMATION_COMPLETED, SubjectRef.automation(autoId),
                new AutomationCompletedEvent(runId.value(), "COMPLETED", 1234L, 1, 1, null, null),
                corr, trigId);
        return runId;
    }

    /**
     * Seeds a run whose single command action issued no command and completed {@code "skipped"}
     * at the given instant (the Doc 07 §3.9 skip shape).
     */
    private RunId seedSkippedRun(AutomationId autoId, EntityId target, Instant completedTime) {
        EventEnvelope trig = publishRoot("state_changed", SubjectRef.entity(target),
                new StateChangedEvent("motion", str("idle"), str("active"), eventId()));
        Ulid corr = trig.causalContext().correlationId();
        Ulid trigId = trig.eventId().value();
        RunId runId = new RunId(ulid());
        publishDerived(EventTypes.AUTOMATION_TRIGGERED, SubjectRef.automation(autoId),
                new AutomationTriggeredEvent(runId.value(), trig.eventId(), List.of("t1"),
                        Map.of("action:0", Set.of(target)), "hash", 0), corr, trigId);
        publishDerived(EventTypes.AUTOMATION_ACTION_STARTED, SubjectRef.automation(autoId),
                new AutomationActionStartedEvent(runId.value(), 0, "CommandAction", List.of(target)),
                corr, trigId);
        publishDerivedAt(EventTypes.AUTOMATION_ACTION_COMPLETED, SubjectRef.automation(autoId),
                new AutomationActionCompletedEvent(runId.value(), 0, "skipped", "target unavailable"),
                corr, trigId, completedTime);
        publishDerived(EventTypes.AUTOMATION_COMPLETED, SubjectRef.automation(autoId),
                new AutomationCompletedEvent(runId.value(), "COMPLETED", 1234L, 1, 0, null, null),
                corr, trigId);
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
        return publishDerivedAt(eventType, subject, payload, correlationId, causationId, FIXED_INSTANT);
    }

    /** As {@link #publishDerived} with an explicit envelope {@code eventTime} ({@code null} allowed). */
    private EventEnvelope publishDerivedAt(String eventType, SubjectRef subject, DomainEvent payload,
                                           Ulid correlationId, Ulid causationId, Instant eventTime) {
        EventDraft draft = new EventDraft(eventType, 1, eventTime, subject,
                EventPriority.NORMAL, EventOrigin.AUTOMATION, payload, null, null);
        try {
            return store.publish(draft, CausalContext.chain(correlationId, causationId));
        } catch (SequenceConflictException e) {
            throw new AssertionError("seed publish failed", e);
        }
    }
}
