/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import com.homesynapse.automation.NonFiringExplanation;
import com.homesynapse.automation.NonFiringExplanation.LastEvaluationView;
import com.homesynapse.automation.NonFiringExplanation.NonFiringVerdict;
import com.homesynapse.automation.RunExplanation;
import com.homesynapse.automation.RunId;
import com.homesynapse.platform.identity.AutomationId;

import java.time.Instant;

/**
 * Test fixture for {@link NonFiringExplanation} (EXPLAIN-114b). The record has ONE constructor —
 * the canonical thirteen-component form — and this fixture is where the endpoint tests get the
 * shorter shapes the deleted 8-, 9- and 10-component convenience constructors used to provide.
 *
 * <p>{@link #of} takes the eight components every fixture names and defaults the five nullable
 * tail components ({@code noCommandsIssued}, {@code triggerRef}, {@code disabledAt},
 * {@code disabledReason}, {@code definitionKey}) to {@code null} — exactly what the deleted
 * conveniences delegated. The {@code with…} variants set one tail fact each and return a new
 * fixture; {@link #build()} constructs the record through the canonical constructor, so the
 * compact constructor's null checks apply unchanged. A new additive component is a field and a
 * {@code with…} here, never a constructor on the record.</p>
 *
 * <p>Immutable. Test-only — not part of the rest-api module's exported API.</p>
 */
final class NonFiringExplanations {

    private final AutomationId automationId;
    private final String automationName;
    private final boolean enabled;
    private final NonFiringVerdict verdict;
    private final RunId lastRelevantRunId;
    private final String explanation;
    private final String triggerSummary;
    private final LastEvaluationView lastEvaluation;
    private final Boolean noCommandsIssued;
    private final RunExplanation.SubjectRefView triggerRef;
    private final Instant disabledAt;
    private final String disabledReason;
    private final String definitionKey;

    private NonFiringExplanations(AutomationId automationId, String automationName, boolean enabled,
                                  NonFiringVerdict verdict, RunId lastRelevantRunId,
                                  String explanation, String triggerSummary,
                                  LastEvaluationView lastEvaluation, Boolean noCommandsIssued,
                                  RunExplanation.SubjectRefView triggerRef, Instant disabledAt,
                                  String disabledReason, String definitionKey) {
        this.automationId = automationId;
        this.automationName = automationName;
        this.enabled = enabled;
        this.verdict = verdict;
        this.lastRelevantRunId = lastRelevantRunId;
        this.explanation = explanation;
        this.triggerSummary = triggerSummary;
        this.lastEvaluation = lastEvaluation;
        this.noCommandsIssued = noCommandsIssued;
        this.triggerRef = triggerRef;
        this.disabledAt = disabledAt;
        this.disabledReason = disabledReason;
        this.definitionKey = definitionKey;
    }

    /**
     * The eight-component shape (what the deleted 8-arg constructor accepted): the five nullable
     * tail components are {@code null} until a {@code with…} sets one.
     */
    static NonFiringExplanations of(AutomationId automationId, String automationName,
                                    boolean enabled, NonFiringVerdict verdict,
                                    RunId lastRelevantRunId, String explanation,
                                    String triggerSummary, LastEvaluationView lastEvaluation) {
        return new NonFiringExplanations(automationId, automationName, enabled, verdict,
                lastRelevantRunId, explanation, triggerSummary, lastEvaluation,
                null, null, null, null, null);
    }

    /** The v1.1.2 skip marker (the deleted 9-arg constructor's ninth argument). */
    NonFiringExplanations withNoCommandsIssued(Boolean value) {
        return new NonFiringExplanations(automationId, automationName, enabled, verdict,
                lastRelevantRunId, explanation, triggerSummary, lastEvaluation, value,
                triggerRef, disabledAt, disabledReason, definitionKey);
    }

    /** The v1.1.3 first-trigger reference (the deleted 10-arg constructor's tenth argument). */
    NonFiringExplanations withTriggerRef(RunExplanation.SubjectRefView value) {
        return new NonFiringExplanations(automationId, automationName, enabled, verdict,
                lastRelevantRunId, explanation, triggerSummary, lastEvaluation, noCommandsIssued,
                value, disabledAt, disabledReason, definitionKey);
    }

    /** The v1.1.4 disable facts, set together as the {@code DISABLED} verdict carries them. */
    NonFiringExplanations withDisabledFacts(Instant at, String reason) {
        return new NonFiringExplanations(automationId, automationName, enabled, verdict,
                lastRelevantRunId, explanation, triggerSummary, lastEvaluation, noCommandsIssued,
                triggerRef, at, reason, definitionKey);
    }

    /** The v1.1.4 stable definition key. */
    NonFiringExplanations withDefinitionKey(String value) {
        return new NonFiringExplanations(automationId, automationName, enabled, verdict,
                lastRelevantRunId, explanation, triggerSummary, lastEvaluation, noCommandsIssued,
                triggerRef, disabledAt, disabledReason, value);
    }

    /** The record, through its one constructor (the compact null checks apply here). */
    NonFiringExplanation build() {
        return new NonFiringExplanation(automationId, automationName, enabled, verdict,
                lastRelevantRunId, explanation, triggerSummary, lastEvaluation, noCommandsIssued,
                triggerRef, disabledAt, disabledReason, definitionKey);
    }
}
