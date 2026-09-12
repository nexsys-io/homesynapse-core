/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.time.Instant;
import java.util.Objects;

import com.homesynapse.platform.identity.AutomationId;

/**
 * The automation read-side "why did this <em>not</em> fire?" projection for one automation
 * (Doc 16 §4 type name; the frozen v1.1 §B3 verdict surface). Assembled by
 * {@link ExplanationService#explainNonFiring(AutomationId, long)} purely from the immutable log
 * (terminal-run records) + the {@link AutomationRegistry} definition; never persisted and minting
 * no event (INV-SA-03 / SP2).
 *
 * <p><strong>V1 field subset (DP-B1).</strong> This record carries the frozen V1 slice:
 * {@link #verdict} (the 4-value {@link NonFiringVerdict}), {@link #explanation},
 * {@link #triggerSummary}, {@link #lastEvaluation}, and {@link #lastRelevantRunId}. The Doc-16
 * §4 {@code SuppressionReason} (7-value: {@code MODE_SUPPRESSED}, {@code CASCADE_LOOP},
 * {@code CASCADE_DEPTH_EXCEEDED}, {@code CONDITION_NOT_MET}, {@code DEFINITION_NOT_LOADED},
 * {@code TARGET_UNAVAILABLE}, {@code READ_FAILED_CLOSED}) and its per-{@code (automation,
 * triggering-event)} keying are the deep "why did the trigger not match" diagnosis that V1 defers
 * to <strong>post-V1</strong>; that richer surface is an additive enrichment of this same record,
 * not a replacement. Do not conflate {@link NonFiringVerdict} (the V1 dashboard vocabulary) with
 * {@code SuppressionReason}.</p>
 *
 * <p>The rest-api layer renders {@link #verdict} to its wire string via {@code name()} and maps
 * the (nullable) {@link #lastEvaluation} / {@link #lastRelevantRunId} to the frozen JSON; the
 * internal types never appear on the wire as objects (LTD-04: ULIDs are Crockford Base32 strings
 * at the boundary). Since v1.1.3 it also renders {@link #triggerRef} as the same
 * {@code {type, id}} map the causal chain serves for {@code trigger.subjectRef}; since v1.1.4
 * (EXPLAIN-114a) it renders {@link #disabledAt} as ISO-8601 and {@link #disabledReason} /
 * {@link #definitionKey} as strings, each JSON null when absent.</p>
 *
 * @param automationId      the diagnosed automation; never {@code null}
 * @param automationName    the display name from the definition; never {@code null}
 * @param enabled           whether the automation is currently enabled
 * @param verdict           the V1 non-firing verdict; never {@code null}
 * @param lastRelevantRunId the most-recent relevant terminal Run, or {@code null} when none exists
 *                          in the window (the {@code NEVER_TRIGGERED}-with-no-run case)
 * @param explanation       a plain-language sentence (Register C); never {@code null}
 * @param triggerSummary    what would fire this, in plain words; never {@code null}
 * @param lastEvaluation    the most-recent evaluation snapshot, or {@code null} when no run exists
 * @param noCommandsIssued  the v1.1.2 skip marker (CORE-P2): {@code Boolean.TRUE} exactly when the
 *                          verdict derives from a terminal {@code COMPLETED} run whose payload has
 *                          {@code actionCount() > 0 && commandCount() == 0} — the run fired but
 *                          issued zero device commands (all device actions skipped per Doc 07
 *                          §3.9, or none defined); {@code null} in every other construction
 *                          (never {@code false} — absent means "not the skip case", the
 *                          additive-nullable idiom)
 * @param triggerRef        the v1.1.3 first-trigger entity reference (CG-1, DP-2): the
 *                          {@code {type:"entity", id}} view of the ONE entity the definition's
 *                          FIRST trigger (definition order) addresses by identity — a
 *                          {@code DirectRefSelector}, or a {@code CalendarTrigger}'s calendar
 *                          entity — or {@code null} when the automation has no trigger or its
 *                          first trigger names a set (a group selector), a device
 *                          ({@code ReachabilityTrigger}), or no subject at all; never a
 *                          fabricated id (D5). Multi-trigger automations expose each trigger's
 *                          ref in {@code AutomationSummary.components[].ref}. Nullable by
 *                          contract (the additive-nullable idiom); NOT null-checked
 * @param disabledAt        the v1.1.4 (EXPLAIN-114a) instant of the LATEST
 *                          {@code automation_disabled} event the log holds for this automation
 *                          (its envelope {@code eventTime}, else {@code ingestTime}) — read only
 *                          on the {@code DISABLED} verdict; {@code null} when no such event
 *                          exists (a configuration-disabled automation) and on every other
 *                          verdict. Nullable by contract; NOT null-checked
 * @param disabledReason    the v1.1.4 reason the automation is off: that event's recorded
 *                          {@code reason} ({@code "repeated_failure"} from the failure governor)
 *                          when {@link #disabledAt} is set; the literal {@code "configuration"}
 *                          (DP-6) when the definition is disabled and the log holds no
 *                          {@code automation_disabled}; {@code null} on every other verdict.
 *                          Nullable by contract; NOT null-checked
 * @param definitionKey     the v1.1.4 stable definition key: {@code DefinitionHashes} over the
 *                          registry's current definition (DP-5) — the SAME SHA-256 hex the
 *                          engine stamps on {@code automation_triggered.definitionHash}, so it
 *                          equals the causal chain's {@code definitionKey} for a run of the
 *                          same definition. Set on every construction the registry can answer;
 *                          nullable by contract; NOT null-checked
 */
public record NonFiringExplanation(
        AutomationId automationId,
        String automationName,
        boolean enabled,
        NonFiringVerdict verdict,
        RunId lastRelevantRunId,
        String explanation,
        String triggerSummary,
        LastEvaluationView lastEvaluation,
        Boolean noCommandsIssued,
        RunExplanation.SubjectRefView triggerRef,
        Instant disabledAt,
        String disabledReason,
        String definitionKey) {

    /**
     * Validates the non-nullable components. {@code lastRelevantRunId} and {@code lastEvaluation}
     * are intentionally nullable (the "never triggered, no run" case); {@code noCommandsIssued},
     * {@code triggerRef}, {@code disabledAt}, {@code disabledReason} and {@code definitionKey}
     * are nullable by contract (absent means "not the skip case" / "no single-entity ref" / "no
     * disable fact on the log" / "no key") and are NOT null-checked.
     *
     * @throws NullPointerException if any non-nullable component is {@code null}
     */
    public NonFiringExplanation {
        Objects.requireNonNull(automationId, "automationId must not be null");
        Objects.requireNonNull(automationName, "automationName must not be null");
        Objects.requireNonNull(verdict, "verdict must not be null");
        Objects.requireNonNull(explanation, "explanation must not be null");
        Objects.requireNonNull(triggerSummary, "triggerSummary must not be null");
    }

    /**
     * Convenience constructor for the pre-v1.1.4 ten-component form (test-convenience;
     * production constructs the canonical form): delegates to the canonical constructor with
     * {@code disabledAt = null}, {@code disabledReason = null} and {@code definitionKey = null}
     * (validation lives ONLY in the canonical constructor). The eight- and nine-component
     * conveniences below resolve through this one.
     */
    public NonFiringExplanation(AutomationId automationId, String automationName, boolean enabled,
                                NonFiringVerdict verdict, RunId lastRelevantRunId,
                                String explanation, String triggerSummary,
                                LastEvaluationView lastEvaluation, Boolean noCommandsIssued,
                                RunExplanation.SubjectRefView triggerRef) {
        this(automationId, automationName, enabled, verdict, lastRelevantRunId, explanation,
                triggerSummary, lastEvaluation, noCommandsIssued, triggerRef, null, null, null);
    }

    /**
     * Convenience constructor for the pre-v1.1.3 nine-component form (test-convenience;
     * production constructs the canonical form): delegates to the canonical constructor with
     * {@code triggerRef = null} (validation lives ONLY in the canonical constructor).
     */
    public NonFiringExplanation(AutomationId automationId, String automationName, boolean enabled,
                                NonFiringVerdict verdict, RunId lastRelevantRunId,
                                String explanation, String triggerSummary,
                                LastEvaluationView lastEvaluation, Boolean noCommandsIssued) {
        this(automationId, automationName, enabled, verdict, lastRelevantRunId, explanation,
                triggerSummary, lastEvaluation, noCommandsIssued, null);
    }

    /**
     * Convenience constructor for the pre-v1.1.2 eight-component form (test-convenience;
     * production constructs the canonical form): delegates to the canonical constructor with
     * {@code noCommandsIssued = null} and {@code triggerRef = null} (validation lives ONLY in the
     * canonical constructor). Pre-v1.1.2 call sites compile unchanged through this overload.
     */
    public NonFiringExplanation(AutomationId automationId, String automationName, boolean enabled,
                                NonFiringVerdict verdict, RunId lastRelevantRunId,
                                String explanation, String triggerSummary,
                                LastEvaluationView lastEvaluation) {
        this(automationId, automationName, enabled, verdict, lastRelevantRunId, explanation,
                triggerSummary, lastEvaluation, null, null);
    }

    /**
     * The most-recent evaluation snapshot for the automation. Both components are nullable: there
     * is no relevant run (then the whole view is {@code null}), or the run reached a terminal
     * state where the condition result is not meaningful.
     *
     * @param at               when the run was last evaluated (absolute; replay-deterministic), or {@code null}
     * @param conditionsResult the rendered condition result ({@code "true"}/{@code "false"}), or {@code null}
     */
    public record LastEvaluationView(Instant at, String conditionsResult) {
    }

    /**
     * The frozen v1.1 non-firing verdict vocabulary (§B3) — the four V1 values plus, since
     * v1.1.4 (EXPLAIN-114a, appended LAST), {@link #FIRED_CONFIRMED}. This is the dashboard
     * verdict, NOT a subset of the Doc-16 §4 {@code SuppressionReason}: the values
     * {@code NEVER_TRIGGERED}/{@code ACTED_BUT_UNCONFIRMED}/{@code DISABLED} are dashboard
     * vocabulary, not suppression reasons. The deeper {@code SuppressionReason}-keyed diagnosis is
     * a post-V1 enrichment (DP-B1) and would be an additive sibling, never a breaking rename here.
     * The enum only ever grows at the end (the four-constraint law).
     */
    public enum NonFiringVerdict {

        /** Triggered, but a condition evaluated false — no actions ran. */
        CONDITION_NOT_MET,

        /**
         * No relevant terminal run in the window: the automation has genuinely not run, and
         * {@code lastRelevantRunId} is {@code null}. Until v1.1.3 this value also stood in for
         * the clean-confirmed-success case (DP-B2, with a non-null run id); since v1.1.4 that
         * case is {@link #FIRED_CONFIRMED}, so a {@code NEVER_TRIGGERED} always carries a
         * {@code null} run id.
         */
        NEVER_TRIGGERED,

        /** Fired, but at least one device action did not confirm (unconfirmed or failed). */
        ACTED_BUT_UNCONFIRMED,

        /** The automation is currently disabled — its non-firing reason is that it is off. */
        DISABLED,

        /**
         * The DP-B2 clean-success case (v1.1.4, EXPLAIN-114a): the most recent in-window run
         * completed and every device action confirmed; {@code lastRelevantRunId} is that run.
         * Not a non-firing at all — the automation did what it was asked and the log proves it.
         */
        FIRED_CONFIRMED
    }
}
