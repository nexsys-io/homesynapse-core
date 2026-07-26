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
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link StandardExplanationService}'s M7.5b non-firing projection
 * ({@code explainNonFiring} + {@code listAutomations}). Seeds a deterministic
 * {@link InMemoryEventStore} (FIXED clock) and a {@link AutomationTestSupport.MapAutomationRegistry},
 * then asserts the DP-B2 verdict matrix, the registry list projection, and INV-SA-03 (zero writes).
 */
@DisplayName("StandardExplanationService — non-firing projection (M7.5b)")
final class NonFiringExplanationServiceTest {

    private InMemoryEventStore store;
    private AutomationTestSupport.MapAutomationRegistry registry;
    private ExplanationService service;

    /** Per-action confirmation shape to seed. */
    private enum ConfirmKind { CONFIRMED, UNCONFIRMED, FAILED, DISPATCHED }

    NonFiringExplanationServiceTest() {
    }

    @BeforeEach
    void setUp() {
        store = new InMemoryEventStore(FIXED_CLOCK);
        registry = new AutomationTestSupport.MapAutomationRegistry();
        service = ExplanationService.over(store, registry);
    }

    // ---- explainNonFiring: verdict matrix -----------------------------------

    @Test
    @DisplayName("a disabled automation always reports DISABLED, regardless of run history")
    void nonFiring_disabledAutomation_returnsDisabled() {
        AutomationId autoId = automationId();
        registry.add(definition(autoId, "Night Arrival", false, entityId()));
        // Even with a recent terminal run, DISABLED short-circuits.
        seedCompleted(autoId, "CONDITION_NOT_MET", null);

        NonFiringExplanation result = service.explainNonFiring(autoId, 0).orElseThrow();

        assertThat(result.verdict()).isEqualTo(NonFiringExplanation.NonFiringVerdict.DISABLED);
        assertThat(result.enabled()).isFalse();
        assertThat(result.lastRelevantRunId()).isNull();
        assertThat(result.lastEvaluation()).isNull();
        assertThat(result.explanation()).contains("disabled");
        assertThat(result.triggerSummary()).isNotBlank();
    }

    @Test
    @DisplayName("an enabled automation with no run in the window reports NEVER_TRIGGERED")
    void nonFiring_noRunInWindow_returnsNeverTriggered() {
        AutomationId autoId = automationId();
        registry.add(definition(autoId, "Porch Light", true, entityId()));

        NonFiringExplanation result = service.explainNonFiring(autoId, 0).orElseThrow();

        assertThat(result.verdict()).isEqualTo(NonFiringExplanation.NonFiringVerdict.NEVER_TRIGGERED);
        assertThat(result.lastRelevantRunId()).isNull();
        assertThat(result.lastEvaluation()).isNull();
        // The definition carries a single StateTrigger; triggerSummary strips the "Trigger" suffix
        // and humanizes → "state" (a StateChangeTrigger would render "state change").
        assertThat(result.triggerSummary()).isEqualTo("state");
    }

    @Test
    @DisplayName("a most-recent CONDITION_NOT_MET run reports CONDITION_NOT_MET")
    void nonFiring_conditionNotMetRun_returnsConditionNotMet() {
        AutomationId autoId = automationId();
        registry.add(definition(autoId, "Dusk Lights", true, entityId()));
        RunId run = seedCompleted(autoId, "CONDITION_NOT_MET", null);

        NonFiringExplanation result = service.explainNonFiring(autoId, 0).orElseThrow();

        assertThat(result.verdict())
                .isEqualTo(NonFiringExplanation.NonFiringVerdict.CONDITION_NOT_MET);
        assertThat(result.lastRelevantRunId()).isEqualTo(run);
        assertThat(result.lastEvaluation()).isNotNull();
        assertThat(result.lastEvaluation().conditionsResult()).isEqualTo("false");
    }

    @Test
    @DisplayName("a COMPLETED run with an unconfirmed action reports ACTED_BUT_UNCONFIRMED")
    void nonFiring_unconfirmedAction_returnsActedButUnconfirmed() {
        AutomationId autoId = automationId();
        EntityId target = entityId();
        registry.add(definition(autoId, "Garage", true, target));
        RunId run = seedRun(autoId, target, "COMPLETED", null, ConfirmKind.UNCONFIRMED);

        NonFiringExplanation result = service.explainNonFiring(autoId, 0).orElseThrow();

        assertThat(result.verdict())
                .isEqualTo(NonFiringExplanation.NonFiringVerdict.ACTED_BUT_UNCONFIRMED);
        assertThat(result.lastRelevantRunId()).isEqualTo(run);
        assertThat(result.lastEvaluation().conditionsResult()).isEqualTo("true");
    }

    @Test
    @DisplayName("a COMPLETED run with a failed action reports ACTED_BUT_UNCONFIRMED")
    void nonFiring_failedAction_returnsActedButUnconfirmed() {
        AutomationId autoId = automationId();
        EntityId target = entityId();
        registry.add(definition(autoId, "Hallway", true, target));
        RunId run = seedRun(autoId, target, "COMPLETED", null, ConfirmKind.FAILED);

        NonFiringExplanation result = service.explainNonFiring(autoId, 0).orElseThrow();

        assertThat(result.verdict())
                .isEqualTo(NonFiringExplanation.NonFiringVerdict.ACTED_BUT_UNCONFIRMED);
        assertThat(result.lastRelevantRunId()).isEqualTo(run);
    }

    @Test
    @DisplayName("DP-B2: a clean confirmed run reports NEVER_TRIGGERED with a non-null run id")
    void nonFiring_cleanConfirmedRun_perDpB2Default() {
        AutomationId autoId = automationId();
        EntityId target = entityId();
        registry.add(definition(autoId, "Welcome", true, target));
        RunId run = seedRun(autoId, target, "COMPLETED", null, ConfirmKind.CONFIRMED);

        NonFiringExplanation result = service.explainNonFiring(autoId, 0).orElseThrow();

        // The frozen 4-value enum has no "fired fine" value; DP-B2 reports NEVER_TRIGGERED but with
        // a NON-NULL lastRelevantRunId so the UI can distinguish "ran fine" from "never ran".
        assertThat(result.verdict()).isEqualTo(NonFiringExplanation.NonFiringVerdict.NEVER_TRIGGERED);
        assertThat(result.lastRelevantRunId()).isEqualTo(run);
        assertThat(result.explanation()).contains("last fired and confirmed");
    }

    @Test
    @DisplayName("explainNonFiring returns empty for an automation unknown to the registry")
    void nonFiring_unknownAutomation_empty() {
        assertThat(service.explainNonFiring(automationId(), 0)).isEmpty();
    }

    @Test
    @DisplayName("a run before expectedSince is outside the window — reports NEVER_TRIGGERED")
    void nonFiring_respectsExpectedSinceWindow() {
        AutomationId autoId = automationId();
        registry.add(definition(autoId, "Evening", true, entityId()));
        seedCompleted(autoId, "CONDITION_NOT_MET", null);
        long afterTheOldRun = store.latestPosition();

        NonFiringExplanation result =
                service.explainNonFiring(autoId, afterTheOldRun + 1).orElseThrow();

        assertThat(result.verdict()).isEqualTo(NonFiringExplanation.NonFiringVerdict.NEVER_TRIGGERED);
        assertThat(result.lastRelevantRunId()).isNull();
    }

    // ---- explainNonFiring: silent-skip honesty (DP-2, v1.1.2) ----------------

    @Test
    @DisplayName("a COMPLETED run that issued zero commands reports ACTED_BUT_UNCONFIRMED + marker")
    void completedCommandless_actedButUnconfirmed_marker() {
        AutomationId autoId = automationId();
        registry.add(definition(autoId, "Scenes", true, entityId()));
        RunId run = seedCompleted(autoId, "COMPLETED", null, 1234L, 3, 0);

        NonFiringExplanation result = service.explainNonFiring(autoId, 0).orElseThrow();

        assertThat(result.verdict())
                .isEqualTo(NonFiringExplanation.NonFiringVerdict.ACTED_BUT_UNCONFIRMED);
        assertThat(result.noCommandsIssued()).isEqualTo(Boolean.TRUE);
        assertThat(result.explanation()).isEqualTo("Automation 'Scenes' fired, but issued no "
                + "device commands — its device actions were skipped or issued nothing (targets "
                + "unavailable or no device actions defined).");
        // The false-verdict boundary: the old clean-success sentence must be unreachable here.
        assertThat(result.explanation()).doesNotContain("fired and confirmed");
        assertThat(result.lastRelevantRunId()).isEqualTo(run);
        assertThat(result.lastEvaluation().conditionsResult()).isEqualTo("true");
    }

    @Test
    @DisplayName("a COMPLETED run with confirmed commands keeps the DP-B2 clean path, marker absent")
    void completedWithConfirmedCommands_cleanPathUnchanged() {
        AutomationId autoId = automationId();
        EntityId target = entityId();
        registry.add(definition(autoId, "Porch", true, target));
        RunId run = seedRun(autoId, target, "COMPLETED", null, ConfirmKind.CONFIRMED);

        NonFiringExplanation result = service.explainNonFiring(autoId, 0).orElseThrow();

        assertThat(result.verdict())
                .isEqualTo(NonFiringExplanation.NonFiringVerdict.NEVER_TRIGGERED);
        assertThat(result.lastRelevantRunId()).isEqualTo(run);
        assertThat(result.noCommandsIssued()).isNull();
    }

    @Test
    @DisplayName("a COMPLETED run with an unconfirmed action behaves as today, marker absent")
    void completedWithUnconfirmed_unchanged() {
        AutomationId autoId = automationId();
        EntityId target = entityId();
        registry.add(definition(autoId, "Closet", true, target));
        RunId run = seedRun(autoId, target, "COMPLETED", null, ConfirmKind.UNCONFIRMED);

        NonFiringExplanation result = service.explainNonFiring(autoId, 0).orElseThrow();

        assertThat(result.verdict())
                .isEqualTo(NonFiringExplanation.NonFiringVerdict.ACTED_BUT_UNCONFIRMED);
        assertThat(result.lastRelevantRunId()).isEqualTo(run);
        assertThat(result.noCommandsIssued()).isNull();
    }

    // ---- explainNonFiring: evaluatedAt derivation (DP-3b, v1.1.2) ------------

    @Test
    @DisplayName("lastEvaluation.at equals the terminal envelope's inherited eventTime exactly (DP-G)")
    void evaluatedAt_equalsEventTime() {
        AutomationId autoId = automationId();
        registry.add(definition(autoId, "Dusk", true, entityId()));
        seedCompleted(autoId, "CONDITION_NOT_MET", null, 34204L, 1, 1);

        NonFiringExplanation result = service.explainNonFiring(autoId, 0).orElseThrow();

        assertThat(result.lastEvaluation().at()).isEqualTo(FIXED_INSTANT);
    }

    @Test
    @DisplayName("with a null eventTime, lastEvaluation.at falls back to ingestTime minus the duration")
    void evaluatedAt_ingestFallback() {
        AutomationId autoId = automationId();
        registry.add(definition(autoId, "Dawn", true, entityId()));
        seedCompletedNullEventTime(autoId, "CONDITION_NOT_MET", 34204L);

        NonFiringExplanation result = service.explainNonFiring(autoId, 0).orElseThrow();

        // InMemoryEventStore stamps ingestTime = clock.instant() (FIXED_CLOCK) at append.
        assertThat(result.lastEvaluation().at()).isEqualTo(FIXED_INSTANT.minusMillis(34204L));
    }

    // ---- listAutomations ----------------------------------------------------

    @Test
    @DisplayName("listAutomations projects the registry with components and a best-effort lastRunId")
    void listAutomations_projectsRegistry() {
        AutomationId ran = automationId();
        AutomationId neverRan = automationId();
        EntityId target = entityId();
        registry.add(fullDefinition(ran, "Has Run", true, target));
        registry.add(definition(neverRan, "Never Run", false, entityId()));
        RunId lastRun = seedCompleted(ran, "COMPLETED", null);

        List<AutomationSummary> summaries = service.listAutomations();

        assertThat(summaries).hasSize(2);
        AutomationSummary hasRun = byId(summaries, ran);
        assertThat(hasRun.name()).isEqualTo("Has Run");
        assertThat(hasRun.enabled()).isTrue();
        assertThat(hasRun.components()).isNotEmpty();
        // fullDefinition carries one trigger + one condition + one action.
        assertThat(hasRun.components()).extracting(AutomationSummary.ComponentView::type)
                .containsExactly("StateTrigger", "StateCondition", "CommandAction");
        assertThat(hasRun.lastRunId()).isEqualTo(lastRun);

        AutomationSummary neverRunSummary = byId(summaries, neverRan);
        assertThat(neverRunSummary.enabled()).isFalse();
        assertThat(neverRunSummary.components()).isNotEmpty();
        assertThat(neverRunSummary.lastRunId()).isNull();
    }

    @Test
    @DisplayName("listAutomations returns an empty list (not an error) when none are loaded")
    void listAutomations_emptyRegistry() {
        assertThat(service.listAutomations()).isEmpty();
    }

    // ---- INV-SA-03: pure projection -----------------------------------------

    @Test
    @DisplayName("the projection writes nothing — latestPosition is unchanged after both reads")
    void projection_writesNothing() {
        AutomationId autoId = automationId();
        EntityId target = entityId();
        registry.add(definition(autoId, "Probe", true, target));
        seedRun(autoId, target, "COMPLETED", null, ConfirmKind.CONFIRMED);
        long before = store.latestPosition();

        service.explainNonFiring(autoId, 0);
        service.listAutomations();

        assertThat(store.latestPosition()).isEqualTo(before);
    }

    // ---- helpers ------------------------------------------------------------

    private static AutomationSummary byId(List<AutomationSummary> summaries, AutomationId id) {
        return summaries.stream().filter(s -> s.automationId().equals(id)).findFirst().orElseThrow();
    }

    /** An automation with a single trigger (no conditions/actions). */
    private static AutomationDefinition definition(AutomationId autoId, String name, boolean enabled,
                                                   EntityId entity) {
        return new AutomationDefinition(autoId, "slug-" + name, name, null, enabled,
                ConcurrencyMode.SINGLE, 1, MaxExceededSeverity.INFO, 0,
                List.of(new StateTrigger(new DirectRefSelector(entity), "motion", "active", null,
                        "t1")),
                List.of(), List.of());
    }

    /** An automation carrying one trigger + one condition + one action (full component set). */
    private static AutomationDefinition fullDefinition(AutomationId autoId, String name,
                                                       boolean enabled, EntityId entity) {
        return new AutomationDefinition(autoId, "slug-" + name, name, null, enabled,
                ConcurrencyMode.SINGLE, 1, MaxExceededSeverity.INFO, 0,
                List.of(new StateTrigger(new DirectRefSelector(entity), "motion", "active", null,
                        "t1")),
                List.of(new StateCondition(new DirectRefSelector(entity), "motion", "active")),
                List.of(new CommandAction(new DirectRefSelector(entity), "turn_on", Map.of(),
                        UnavailablePolicy.SKIP)));
    }

    /** Seeds only a terminal {@code automation_completed} marker. */
    private RunId seedCompleted(AutomationId autoId, String finalStatus, String failureReason) {
        return seedCompleted(autoId, finalStatus, failureReason, 1234L, 1, 1);
    }

    /**
     * Seeds a terminal marker with explicit duration and action/command counts (the DP-2 payload
     * arithmetic + DP-3b duration fixtures; eventTime present).
     */
    private RunId seedCompleted(AutomationId autoId, String finalStatus, String failureReason,
                                long durationMs, int actionCount, int commandCount) {
        RunId runId = new RunId(ulid());
        publishRoot(EventTypes.AUTOMATION_COMPLETED, SubjectRef.automation(autoId),
                new AutomationCompletedEvent(runId.value(), finalStatus, durationMs, actionCount,
                        commandCount, failureReason, null));
        return runId;
    }

    /**
     * Seeds a terminal marker whose envelope {@code eventTime} is null (the ingest-fallback
     * fixture — the {@link EventDraft} accepts a null eventTime, as CMD-API roots publish live).
     */
    private RunId seedCompletedNullEventTime(AutomationId autoId, String finalStatus,
                                             long durationMs) {
        RunId runId = new RunId(ulid());
        EventDraft draft = new EventDraft(EventTypes.AUTOMATION_COMPLETED, 1, null,
                SubjectRef.automation(autoId), EventPriority.NORMAL, EventOrigin.AUTOMATION,
                new AutomationCompletedEvent(runId.value(), finalStatus, durationMs, 1, 1,
                        null, null), null, null);
        try {
            store.publishRoot(draft);
        } catch (SequenceConflictException e) {
            throw new AssertionError("seed publish failed", e);
        }
        return runId;
    }

    /** Seeds a full run chain on its own (root) correlation, with a confirmation of the given kind. */
    private RunId seedRun(AutomationId autoId, EntityId target, String finalStatus,
                          String abortReason, ConfirmKind kind) {
        EventEnvelope trig = publishRoot("state_changed", SubjectRef.entity(target),
                new StateChangedEvent("motion", str("idle"), str("active"), eventId()));
        Ulid corr = trig.causalContext().correlationId();
        Ulid trigId = trig.eventId().value();
        RunId runId = new RunId(ulid());
        publishDerived(EventTypes.AUTOMATION_TRIGGERED, SubjectRef.automation(autoId),
                new AutomationTriggeredEvent(runId.value(), trig.eventId(), List.of("t1"),
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
                // no confirmation event — confirmation-disabled or still in-flight
            }
        }
        publishDerived(EventTypes.AUTOMATION_ACTION_COMPLETED, SubjectRef.automation(autoId),
                new AutomationActionCompletedEvent(runId.value(), 0, "success", null), corr, trigId);
        publishDerived(EventTypes.AUTOMATION_COMPLETED, SubjectRef.automation(autoId),
                new AutomationCompletedEvent(runId.value(), finalStatus, 1234L, 1, 1, null,
                        abortReason), corr, trigId);
        return runId;
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
