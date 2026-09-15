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

import com.homesynapse.device.EntityRole;
import com.homesynapse.event.AutomationActionCompletedEvent;
import com.homesynapse.event.AutomationActionStartedEvent;
import com.homesynapse.event.AutomationCompletedEvent;
import com.homesynapse.event.AutomationConditionEvaluatedEvent;
import com.homesynapse.event.AutomationConditionEvaluatedEvent.EvaluatedEntityState;
import com.homesynapse.event.AutomationDisabledEvent;
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
import com.homesynapse.event.EventPage;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventStore;
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
import java.util.ArrayList;
import java.util.HashMap;
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

    /**
     * Mirrors {@code StandardExplanationService.SCAN_BATCH} (private there): the page size the one
     * type-index scan reads with. T1/T2 seed one more than a page so the walk pages twice; T3 pins
     * this mirror against the {@code maxCount} the service actually passes.
     */
    private static final int SCAN_BATCH = 500;

    /** The random component of every {@link #ulidAt} id — a counter, so ids are unique and deterministic. */
    private static long ulidCounter;

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

    // ---- EXPLAIN-114b: the one type-index scan crosses the SCAN_BATCH boundary ----

    @Test
    @DisplayName("listRuns pages across the SCAN_BATCH boundary: the newest five of 501 markers, page 2 seen (T1)")
    void listRuns_pagesAcrossScanBatchBoundary() {
        AutomationId autoId = automationId();
        List<RunId> seeded = new ArrayList<>(SCAN_BATCH + 1);
        for (int i = 0; i < SCAN_BATCH + 1; i++) {
            seeded.add(seedCompleted(autoId, "COMPLETED", null));
        }
        CountingEventStore counting = new CountingEventStore(store);
        ExplanationService paged = ExplanationService.over(counting, registry);

        RunPage page = paged.listRuns(Optional.of(autoId), 0, 5);

        // The 501st marker is alone on page 2 and is the newest — it must lead the page.
        int last = seeded.size() - 1;
        assertThat(page.runs()).extracting(RunSummary::runId).containsExactly(
                seeded.get(last), seeded.get(last - 1), seeded.get(last - 2),
                seeded.get(last - 3), seeded.get(last - 4));
        assertThat(page.hasMore()).isTrue();
        assertThat(counting.reads(EventTypes.AUTOMATION_COMPLETED)).isEqualTo(2);
    }

    @Test
    @DisplayName("latestDisabled scans to the last page: the target's marker behind 500 of another automation (T2)")
    void latestDisabled_seesMarkerOnSecondPage() {
        AutomationId other = automationId();
        AutomationId autoId = automationId();
        registry.add(definition(autoId, "Night Arrival", false, entityId()));
        for (int i = 0; i < SCAN_BATCH; i++) {
            seedDisabled(other, "repeated_failure", 3, FIXED_INSTANT);
        }
        Instant disabledAt = FIXED_INSTANT.plusSeconds(7);
        seedDisabled(autoId, "repeated_failure", 3, disabledAt);
        CountingEventStore counting = new CountingEventStore(store);
        ExplanationService paged = ExplanationService.over(counting, registry);

        NonFiringExplanation result = paged.explainNonFiring(autoId, 0).orElseThrow();

        assertThat(result.verdict()).isEqualTo(NonFiringExplanation.NonFiringVerdict.DISABLED);
        assertThat(result.disabledAt()).isEqualTo(disabledAt);
        assertThat(result.disabledReason()).isEqualTo("repeated_failure");
        assertThat(counting.reads(EventTypes.AUTOMATION_DISABLED)).isEqualTo(2);
    }

    @Test
    @DisplayName("explainRun's fallback scan stops at the first automation_triggered match: one page read of two after a hint miss (T3)")
    void explainRun_stopsAtFirstTriggeredMatch() {
        // Since EXPLAIN-114c the run id's own instant is read first; a run id minted an hour after
        // its marker (a stale inherited eventTime, the class the window cannot reach) misses the
        // hint window and takes the fallback type scan — the EXPLAIN-114b early stop, preserved.
        RunId runId = seedRunAt(automationId(), entityId(), ulidAt(FIXED_INSTANT.plusSeconds(3600)),
                FIXED_INSTANT);
        for (int i = 0; i < SCAN_BATCH; i++) {
            seedTriggeredMarker(automationId());
        }
        // Construction check: the type index spans two pages and the run's marker is on page 1.
        assertThat(store.readByType(EventTypes.AUTOMATION_TRIGGERED, 0, SCAN_BATCH).hasMore()).isTrue();
        CountingEventStore counting = new CountingEventStore(store);
        ExplanationService paged = ExplanationService.over(counting, registry);

        assertThat(paged.explainRun(runId)).isPresent();

        assertThat(counting.timeRangeReads()).isEqualTo(1);
        assertThat(counting.reads(EventTypes.AUTOMATION_TRIGGERED)).isEqualTo(1);
        assertThat(counting.lastMaxCount()).isEqualTo(SCAN_BATCH);
    }

    // ---- EXPLAIN-114c: the by-id read (K2) and the cursor stop (IR-2) ----------

    @Test
    @DisplayName("explainRun finds the newest run through the by-id hint: time-window reads, zero type-index pages (T1)")
    void locateTriggered_findsNewestRunByIdHint() {
        AutomationId autoId = automationId();
        EntityId target = entityId();
        // A ticking seed (DP-1): 2,001 markers a second apart (five type-index pages), then the
        // newest run at the next second with its id minted at that same instant — the shape the
        // engine produces (StandardRunManager mints the id, then publishes the marker).
        for (int i = 0; i < 4 * SCAN_BATCH + 1; i++) {
            seedTriggeredMarkerAt(automationId(), FIXED_INSTANT.plusSeconds(i));
        }
        Instant newestAt = FIXED_INSTANT.plusSeconds(4 * SCAN_BATCH + 1);
        RunId newest = seedRunAt(autoId, target, ulidAt(newestAt), newestAt);
        CountingEventStore counting = new CountingEventStore(store);
        ExplanationService paged = ExplanationService.over(counting, registry);

        assertThat(paged.explainRun(newest)).isPresent();

        assertThat(counting.timeRangeReads()).isGreaterThanOrEqualTo(1);
        assertThat(counting.reads(EventTypes.AUTOMATION_TRIGGERED)).isZero();
        // The window is well-formed by construction (t − 1 s < t + 60 s): the store's
        // IllegalArgumentException on an inverted range cannot occur (§4, asserted anyway).
        assertThat(counting.lastTimeRangeFrom()).isBefore(counting.lastTimeRangeTo());
        assertThat(counting.lastTimeRangeFrom()).isEqualTo(newestAt.minusSeconds(1));
        assertThat(counting.lastTimeRangeTo()).isEqualTo(newestAt.plusSeconds(60));
    }

    @Test
    @DisplayName("listRuns stops at the cursor: a cursor inside page 1 of a 501-marker index reads one page, not two (T3)")
    void listRuns_stopsAtCursor() {
        AutomationId autoId = automationId();
        List<RunId> seeded = new ArrayList<>(SCAN_BATCH + 1);
        for (int i = 0; i < 10; i++) {
            seeded.add(seedCompleted(autoId, "COMPLETED", null));
        }
        // The 10th marker's position: as the cursor it is excluded, and so is everything after it.
        long cursor = store.latestPosition();
        for (int i = 10; i < SCAN_BATCH + 1; i++) {
            seeded.add(seedCompleted(autoId, "COMPLETED", null));
        }
        CountingEventStore counting = new CountingEventStore(store);
        ExplanationService paged = ExplanationService.over(counting, registry);

        RunPage page = paged.listRuns(Optional.of(autoId), cursor, 5);

        // Below the cursor sit markers 1–9 (positions ascend): the newest five of them, newest-first.
        assertThat(page.runs()).extracting(RunSummary::runId).containsExactly(
                seeded.get(8), seeded.get(7), seeded.get(6), seeded.get(5), seeded.get(4));
        assertThat(page.hasMore()).isTrue();
        assertThat(counting.reads(EventTypes.AUTOMATION_COMPLETED)).isEqualTo(1);
    }

    // ---- EXPLAIN-114c: conditions[].definition (K3), vouched by the run's definition hash ----

    @Test
    @DisplayName("conditions[].definition is the registry condition at the event's index when the run's hash vouches for it (T4)")
    void buildConditions_definitionVouchedByHash() {
        AutomationId autoId = automationId();
        EntityId target = entityId();
        AutomationDefinition def = definitionWithConditions(autoId, "Vouched", target,
                List.of(new StateCondition(new DirectRefSelector(target), "motion", "active")));
        registry.add(def);
        RunId runId = seedRunWithDefinition(autoId, target, DefinitionHashes.forDefinition(def), 0);

        RunExplanation.ConditionView condition =
                service.explainRun(runId).orElseThrow().conditions().get(0);

        assertThat(condition.expression()).isEqualTo("StateCondition");
        RunExplanation.ConditionDefinitionView definition = condition.definition();
        assertThat(definition).isNotNull();
        assertThat(definition.type()).isEqualTo("StateCondition");
        assertThat(definition.selector()).isEqualTo(target.toString());
        assertThat(definition.attribute()).isEqualTo("motion");
        assertThat(definition.value()).isEqualTo("active");
        assertThat(definition.above()).isNull();
        assertThat(definition.below()).isNull();
        assertThat(definition.after()).isNull();
        assertThat(definition.before()).isNull();
        assertThat(definition.children()).isEmpty();
    }

    @Test
    @DisplayName("a compound condition renders its children, recursively (T4b)")
    void buildConditions_compoundDefinitionRendersChildren() {
        AutomationId autoId = automationId();
        EntityId target = entityId();
        AutomationDefinition def = definitionWithConditions(autoId, "Compound", target,
                List.of(new AndCondition(List.of(
                        new StateCondition(new DirectRefSelector(target), "motion", "active"),
                        new NotCondition(new NumericCondition(
                                new AreaSelector("hall", Set.of(EntityRole.PRIMARY)),
                                "temperature", 20.0, null))))));
        registry.add(def);
        RunId runId = seedRunWithDefinition(autoId, target, DefinitionHashes.forDefinition(def), 0);

        RunExplanation.ConditionDefinitionView definition =
                service.explainRun(runId).orElseThrow().conditions().get(0).definition();

        assertThat(definition.type()).isEqualTo("AndCondition");
        assertThat(definition.selector()).isNull();
        assertThat(definition.children()).hasSize(2);
        RunExplanation.ConditionDefinitionView state = definition.children().get(0);
        assertThat(state.type()).isEqualTo("StateCondition");
        assertThat(state.selector()).isEqualTo(target.toString());
        RunExplanation.ConditionDefinitionView not = definition.children().get(1);
        assertThat(not.type()).isEqualTo("NotCondition");
        assertThat(not.selector()).isNull();
        assertThat(not.children()).singleElement().satisfies(numeric -> {
            assertThat(numeric.type()).isEqualTo("NumericCondition");
            assertThat(numeric.selector()).isEqualTo("area:hall/PRIMARY");
            assertThat(numeric.attribute()).isEqualTo("temperature");
            assertThat(numeric.value()).isNull();
            assertThat(numeric.above()).isEqualTo(20.0);
            assertThat(numeric.below()).isNull();
            assertThat(numeric.children()).isEmpty();
        });
    }

    @Test
    @DisplayName("definition is null when the registry's hash differs from the run's, or the automation is gone — never a guess (T4c)")
    void buildConditions_nullWhenDefinitionChanged() {
        AutomationId autoId = automationId();
        EntityId target = entityId();
        registry.add(definitionWithConditions(autoId, "Changed", target,
                List.of(new StateCondition(new DirectRefSelector(target), "motion", "active"))));
        // The run ran under a definition whose hash is not the registry's current one.
        RunId changed = seedRunWithDefinition(autoId, target, "hash", 0);
        // No registry definition at all.
        AutomationId gone = automationId();
        RunId unknown = seedRunWithDefinition(gone, entityId(), "hash", 0);

        RunExplanation.ConditionView changedView =
                service.explainRun(changed).orElseThrow().conditions().get(0);
        RunExplanation.ConditionView unknownView =
                service.explainRun(unknown).orElseThrow().conditions().get(0);

        assertThat(changedView.expression()).isEqualTo("StateCondition");
        assertThat(changedView.definition()).isNull();
        assertThat(unknownView.definition()).isNull();
    }

    @Test
    @DisplayName("definition is null when the event's index lies past the definition's condition list (T4d)")
    void buildConditions_nullWhenIndexOutOfRange() {
        AutomationId autoId = automationId();
        EntityId target = entityId();
        AutomationDefinition def = definitionWithConditions(autoId, "Short", target,
                List.of(new StateCondition(new DirectRefSelector(target), "motion", "active")));
        registry.add(def);
        RunId runId = seedRunWithDefinition(autoId, target, DefinitionHashes.forDefinition(def), 5);

        RunExplanation.ConditionView condition =
                service.explainRun(runId).orElseThrow().conditions().get(0);

        assertThat(condition.expression()).isEqualTo("StateCondition");
        assertThat(condition.definition()).isNull();
    }

    @Test
    @DisplayName("the renderer's selector forms: the ULID, the slug, kind:value/roles, the tag's four parts, compound parts joined by + (T4e)")
    void conditionDefinitionRenderer_selectorForms() {
        EntityId e1 = entityId();

        assertThat(selectorOf(new DirectRefSelector(e1))).isEqualTo(e1.toString());
        assertThat(selectorOf(new SlugSelector("porch-light"))).isEqualTo("porch-light");
        assertThat(selectorOf(new AreaSelector("hall", Set.of(EntityRole.PRIMARY))))
                .isEqualTo("area:hall/PRIMARY");
        assertThat(selectorOf(new LabelSelector("porch",
                Set.of(EntityRole.PRIMARY, EntityRole.DIAGNOSTIC))))
                .isEqualTo("label:porch/DIAGNOSTIC,PRIMARY");
        assertThat(selectorOf(new TypeSelector("LIGHT", Set.of(EntityRole.PRIMARY))))
                .isEqualTo("type:LIGHT/PRIMARY");
        assertThat(selectorOf(new SemanticTagSelector("room", "porch", MatchMode.EXACT,
                Set.of(EntityRole.PRIMARY))))
                .isEqualTo("tag:room/porch/EXACT/PRIMARY");
        assertThat(selectorOf(new CompoundSelector(List.of(
                new DirectRefSelector(e1), new SlugSelector("porch-light")))))
                .isEqualTo(e1 + "+porch-light");

        RunExplanation.ConditionDefinitionView time =
                ConditionDefinitionRenderer.render(new TimeCondition("22:00", "06:00"));
        assertThat(time.type()).isEqualTo("TimeCondition");
        assertThat(time.selector()).isNull();
        assertThat(time.after()).isEqualTo("22:00");
        assertThat(time.before()).isEqualTo("06:00");
        assertThat(time.children()).isEmpty();

        RunExplanation.ConditionDefinitionView zone =
                ConditionDefinitionRenderer.render(new ZoneCondition());
        assertThat(zone.type()).isEqualTo("ZoneCondition");
        assertThat(zone.selector()).isNull();
        assertThat(zone.attribute()).isNull();
        assertThat(zone.children()).isEmpty();
    }

    private static String selectorOf(Selector selector) {
        return ConditionDefinitionRenderer.render(new StateCondition(selector, "a", "b")).selector();
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
        return seedRunOnCorrelation(autoId, target, corr, trigId, finalStatus, abortReason, kind,
                triggeringEventId, "hash", 0);
    }

    /**
     * Seeds a full CONFIRMED run chain on its own (root) correlation whose
     * {@code automation_triggered} carries {@code definitionHash} and whose one
     * {@code automation_condition_evaluated} carries {@code conditionIndex} — the two facts the
     * K3 hash guard reads (EXPLAIN-114c).
     */
    private RunId seedRunWithDefinition(AutomationId autoId, EntityId target, String definitionHash,
                                        int conditionIndex) {
        EventEnvelope trig = publishRoot("state_changed", SubjectRef.entity(target),
                new StateChangedEvent("motion", str("idle"), str("active"), eventId()));
        return seedRunOnCorrelation(autoId, target, trig.causalContext().correlationId(),
                trig.eventId().value(), "COMPLETED", null, ConfirmKind.CONFIRMED, trig.eventId(),
                definitionHash, conditionIndex);
    }

    private RunId seedRunOnCorrelation(AutomationId autoId, EntityId target, Ulid corr, Ulid trigId,
                                       String finalStatus, String abortReason, ConfirmKind kind,
                                       EventId triggeringEventId, String definitionHash,
                                       int conditionIndex) {
        RunId runId = new RunId(ulid());
        publishDerived(EventTypes.AUTOMATION_TRIGGERED, SubjectRef.automation(autoId),
                new AutomationTriggeredEvent(runId.value(), triggeringEventId, List.of("t1"),
                        Map.of("action:0", Set.of(target)), definitionHash, 0), corr, trigId);
        publishDerived(EventTypes.AUTOMATION_CONDITION_EVALUATED, SubjectRef.automation(autoId),
                new AutomationConditionEvaluatedEvent(runId.value(), conditionIndex, "StateCondition",
                        true, List.of(new EvaluatedEntityState(target, "motion", "active",
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

    /** Seeds an automation_disabled marker on the automation subject with an explicit eventTime. */
    private void seedDisabled(AutomationId autoId, String reason, int failureCount, Instant at) {
        EventDraft draft = new EventDraft(EventTypes.AUTOMATION_DISABLED, 1, at,
                SubjectRef.automation(autoId), EventPriority.NORMAL, EventOrigin.AUTOMATION,
                new AutomationDisabledEvent(autoId, reason, failureCount, 60, "boom", null),
                null, null);
        try {
            store.publishRoot(draft);
        } catch (SequenceConflictException e) {
            throw new AssertionError("seed publish failed", e);
        }
    }

    /** Seeds a bare {@code automation_triggered} marker on its own root correlation (type-index filler). */
    private void seedTriggeredMarker(AutomationId autoId) {
        seedTriggeredMarkerAt(autoId, FIXED_INSTANT);
    }

    /** As {@link #seedTriggeredMarker} with an explicit envelope {@code eventTime} (the ticking seed). */
    private void seedTriggeredMarkerAt(AutomationId autoId, Instant at) {
        publishRootAt(EventTypes.AUTOMATION_TRIGGERED, SubjectRef.automation(autoId),
                new AutomationTriggeredEvent(ulid(), eventId(), List.of("t1"),
                        Map.of("action:0", Set.of(entityId())), "hash", 0), at);
    }

    /**
     * Seeds a full CONFIRMED run chain on its own (root) correlation with an explicit run id and
     * every envelope's {@code eventTime} at {@code at} — the {@link #seedRun} shape with the two
     * facts the by-id read keys on made explicit (EXPLAIN-114c).
     */
    private RunId seedRunAt(AutomationId autoId, EntityId target, Ulid runUlid, Instant at) {
        EventEnvelope trig = publishRootAt("state_changed", SubjectRef.entity(target),
                new StateChangedEvent("motion", str("idle"), str("active"), eventId()), at);
        Ulid corr = trig.causalContext().correlationId();
        Ulid trigId = trig.eventId().value();
        RunId runId = new RunId(runUlid);
        publishDerivedAt(EventTypes.AUTOMATION_TRIGGERED, SubjectRef.automation(autoId),
                new AutomationTriggeredEvent(runId.value(), trig.eventId(), List.of("t1"),
                        Map.of("action:0", Set.of(target)), "hash", 0), corr, trigId, at);
        publishDerivedAt(EventTypes.AUTOMATION_CONDITION_EVALUATED, SubjectRef.automation(autoId),
                new AutomationConditionEvaluatedEvent(runId.value(), 0, "StateCondition", true,
                        List.of(new EvaluatedEntityState(target, "motion", "active",
                                FIXED_INSTANT, null))), corr, trigId, at);
        publishDerivedAt(EventTypes.AUTOMATION_ACTION_STARTED, SubjectRef.automation(autoId),
                new AutomationActionStartedEvent(runId.value(), 0, "CommandAction", List.of(target)),
                corr, trigId, at);
        EventEnvelope cmd = publishDerivedAt(EventTypes.COMMAND_ISSUED, SubjectRef.entity(target),
                new CommandIssuedEvent(target.value(), "turn_on", "{\"level\":75}", 5000,
                        CommandIdempotency.IDEMPOTENT), corr, trigId, at);
        publishDerivedAt(EventTypes.STATE_CONFIRMED, SubjectRef.entity(target),
                new StateConfirmedEvent(cmd.eventId(), eventId(), "on", "true", "true", "exact"),
                corr, cmd.eventId().value(), at);
        publishDerivedAt(EventTypes.AUTOMATION_ACTION_COMPLETED, SubjectRef.automation(autoId),
                new AutomationActionCompletedEvent(runId.value(), 0, "success", null), corr, trigId,
                at);
        publishDerivedAt(EventTypes.AUTOMATION_COMPLETED, SubjectRef.automation(autoId),
                new AutomationCompletedEvent(runId.value(), "COMPLETED", 1234L, 1, 1, null, null),
                corr, trigId, at);
        return runId;
    }

    /**
     * A ULID whose 48-bit timestamp is exactly {@code at} (millisecond precision) and whose
     * random component is a per-class counter: deterministic, unique, and independent of
     * {@code UlidFactory}'s static monotonic guard, which would otherwise carry another test
     * class's later instant into this one and move the by-id hint window.
     */
    private static Ulid ulidAt(Instant at) {
        long n = ++ulidCounter;
        return new Ulid((at.toEpochMilli() << 16) | (n & 0xFFFFL), n);
    }

    private static AutomationDefinition definition(AutomationId autoId, String name, EntityId entity) {
        return definition(autoId, name, true, entity);
    }

    /** An enabled or disabled automation with a single {@code StateTrigger} on {@code entity}. */
    private static AutomationDefinition definition(AutomationId autoId, String name, boolean enabled,
                                                   EntityId entity) {
        return new AutomationDefinition(autoId, "auto-slug", name, null, enabled,
                ConcurrencyMode.SINGLE, 1, MaxExceededSeverity.INFO, 0,
                List.of(new StateTrigger(new DirectRefSelector(entity), "motion", "active", null,
                        "t1")),
                List.of(), List.of());
    }

    /** An enabled automation with a single {@code StateTrigger} on {@code entity} and the given conditions. */
    private static AutomationDefinition definitionWithConditions(AutomationId autoId, String name,
                                                                 EntityId entity,
                                                                 List<ConditionDefinition> conditions) {
        return new AutomationDefinition(autoId, "auto-slug", name, null, true,
                ConcurrencyMode.SINGLE, 1, MaxExceededSeverity.INFO, 0,
                List.of(new StateTrigger(new DirectRefSelector(entity), "motion", "active", null,
                        "t1")),
                conditions, List.of());
    }

    private EventEnvelope publishRoot(String eventType, SubjectRef subject, DomainEvent payload) {
        return publishRootAt(eventType, subject, payload, FIXED_INSTANT);
    }

    /** As {@link #publishRoot} with an explicit envelope {@code eventTime}. */
    private EventEnvelope publishRootAt(String eventType, SubjectRef subject, DomainEvent payload,
                                        Instant eventTime) {
        EventDraft draft = new EventDraft(eventType, 1, eventTime, subject,
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

    /**
     * A read-through {@link EventStore} decorator that counts {@code readByType} calls per event
     * type and {@code readByTimeRange} calls (the by-id hint, EXPLAIN-114c), and records the last
     * page size asked for: the instrument for the early stop (T3), the two-page walks (T1/T2),
     * the hint / fallback arms, and the pin that keeps {@link #SCAN_BATCH} honest. Delegates
     * every read to the seeded store; reads no clock.
     */
    private static final class CountingEventStore implements EventStore {
        private final EventStore delegate;
        private final Map<String, Integer> readsByType = new HashMap<>();
        private int timeRangeReads;
        private Instant lastTimeRangeFrom;
        private Instant lastTimeRangeTo;
        private int lastMaxCount = -1;

        CountingEventStore(EventStore delegate) {
            this.delegate = delegate;
        }

        int reads(String eventType) {
            return readsByType.getOrDefault(eventType, 0);
        }

        int timeRangeReads() {
            return timeRangeReads;
        }

        Instant lastTimeRangeFrom() {
            return lastTimeRangeFrom;
        }

        Instant lastTimeRangeTo() {
            return lastTimeRangeTo;
        }

        int lastMaxCount() {
            return lastMaxCount;
        }

        @Override
        public EventPage readFrom(long afterPosition, int maxCount) {
            return delegate.readFrom(afterPosition, maxCount);
        }

        @Override
        public EventPage readBySubject(SubjectRef subject, long afterSequence, int maxCount) {
            return delegate.readBySubject(subject, afterSequence, maxCount);
        }

        @Override
        public List<EventEnvelope> readByCorrelation(Ulid correlationId) {
            return delegate.readByCorrelation(correlationId);
        }

        @Override
        public EventPage readByType(String eventType, long afterPosition, int maxCount) {
            readsByType.merge(eventType, 1, Integer::sum);
            lastMaxCount = maxCount;
            return delegate.readByType(eventType, afterPosition, maxCount);
        }

        @Override
        public EventPage readByTimeRange(Instant from, Instant to, long afterPosition, int maxCount) {
            timeRangeReads++;
            lastTimeRangeFrom = from;
            lastTimeRangeTo = to;
            lastMaxCount = maxCount;
            return delegate.readByTimeRange(from, to, afterPosition, maxCount);
        }

        @Override
        public long latestPosition() {
            return delegate.latestPosition();
        }
    }
}
