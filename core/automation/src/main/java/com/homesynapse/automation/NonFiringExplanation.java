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
 * at the boundary).</p>
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
 */
public record NonFiringExplanation(
        AutomationId automationId,
        String automationName,
        boolean enabled,
        NonFiringVerdict verdict,
        RunId lastRelevantRunId,
        String explanation,
        String triggerSummary,
        LastEvaluationView lastEvaluation) {

    /**
     * Validates the non-nullable components. {@code lastRelevantRunId} and {@code lastEvaluation}
     * are intentionally nullable (the "never triggered, no run" case).
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
     * The frozen v1.1 non-firing verdict vocabulary (§B3) — exactly four values. This is the V1
     * dashboard verdict, NOT a subset of the Doc-16 §4 {@code SuppressionReason}: the values
     * {@code NEVER_TRIGGERED}/{@code ACTED_BUT_UNCONFIRMED}/{@code DISABLED} are dashboard
     * vocabulary, not suppression reasons. The deeper {@code SuppressionReason}-keyed diagnosis is
     * a post-V1 enrichment (DP-B1) and would be an additive sibling, never a breaking rename here.
     */
    public enum NonFiringVerdict {

        /** Triggered, but a condition evaluated false — no actions ran. */
        CONDITION_NOT_MET,

        /**
         * No relevant terminal run in the window. Either the automation has genuinely not run
         * (then {@code lastRelevantRunId} is {@code null}) or, per DP-B2, the most-recent in-window
         * run was a clean confirmed success (then {@code lastRelevantRunId} is non-null and the
         * explanation distinguishes "ran fine" from "never ran").
         */
        NEVER_TRIGGERED,

        /** Fired, but at least one device action did not confirm (unconfirmed or failed). */
        ACTED_BUT_UNCONFIRMED,

        /** The automation is currently disabled — its non-firing reason is that it is off. */
        DISABLED
    }
}
