/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.homesynapse.event.EventEnvelope;
import com.homesynapse.platform.identity.AutomationId;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.Ulid;

/**
 * Manages automation Run lifecycle, concurrency mode enforcement (§3.6), and
 * cascade governance (§3.7.1).
 *
 * <h2>Deduplication</h2>
 *
 * <p>Runs are deduplicated by {@code (automation_id, triggering_event_id)} (C2).
 * The same event cannot trigger the same automation twice.</p>
 *
 * <h2>Execution Order</h2>
 *
 * <p>When multiple automations are triggered by the same event, they execute in
 * priority descending order, then by {@code automation_id} ascending for
 * deterministic ordering (C3).</p>
 *
 * <p>Thread-safe. All methods may be called concurrently from multiple virtual threads.</p>
 *
 * <p>Defined in Doc 07 §3.7, §8.1.</p>
 *
 * @see ConcurrencyMode
 * @see RunStatus
 * @see RunContext
 */
public interface RunManager {

    /**
     * Initiates a Run after concurrency mode enforcement.
     *
     * <p>Returns empty if the concurrency mode rejects the new Run (e.g.,
     * {@link ConcurrencyMode#SINGLE} with an active Run).</p>
     *
     * <p>Returns empty (with no {@code automation_triggered} event) when the Run is
     * deduplicated (C2), suppressed by cascade governance (depth or cycle), suppressed
     * because the automation is auto-disabled, or dropped by concurrency-mode enforcement.
     * A Run whose conditions evaluate false is still initiated — it returns its {@code RunId}
     * and completes immediately with {@code CONDITION_NOT_MET} without consuming a mode
     * slot (Doc 07 §3.6).</p>
     *
     * @param automation      the automation to run, never {@code null}
     * @param triggeringEvent the event that triggered this Run, never {@code null}
     * @param matchedTriggers indices of the triggers that matched, never {@code null}
     * @param resolvedTargets resolved entity sets keyed by selector label,
     *                        never {@code null}
     * @param parentChain     the causal chain this Run inherits (AMD-91):
     *                        {@link RunCausalChain#root()} for user/device/time-initiated
     *                        (root) Runs; for a cascade Run triggered by parent Run P, the
     *                        caller passes
     *                        {@code P.causalChain().extend(new RunCausalChain.ChainLink(P.runId(), P.automationId()))}.
     *                        Never {@code null}
     * @return the Run ID if initiated, or empty if deduplicated, suppressed, or rejected
     */
    Optional<RunId> initiateRun(
            AutomationDefinition automation,
            EventEnvelope triggeringEvent,
            List<Integer> matchedTriggers,
            Map<String, Set<EntityId>> resolvedTargets,
            RunCausalChain parentChain);

    /**
     * Gets the context of an active Run.
     *
     * @param runId the Run identifier, never {@code null}
     * @return the Run context, or empty if not active
     */
    Optional<RunContext> getActiveRun(RunId runId);

    /**
     * Gets a Run's current or terminal status.
     *
     * @param runId the Run identifier, never {@code null}
     * @return the Run status, never {@code null}
     */
    RunStatus getStatus(RunId runId);

    /**
     * Returns the total number of active Runs across all automations.
     *
     * @return the active Run count, always {@code >= 0}
     */
    int activeRunCount();

    /**
     * Returns the number of active Runs for a specific automation.
     *
     * @param automationId the automation identifier, never {@code null}
     * @return the active Run count for this automation, always {@code >= 0}
     */
    int activeRunCount(AutomationId automationId);

    /**
     * Finalizes zombie Runs at the REPLAY&rarr;LIVE transition (Doc 07 §3.10).
     *
     * <p>A zombie Run is one whose {@code automation_triggered} exists in the immutable
     * event log with no matching {@code automation_completed} — it was in flight when the
     * process died. For each zombie, this publishes an {@code automation_completed} with
     * {@code finalStatus = "INTERRUPTED"} and {@code abortReason = "interrupted_by_crash"},
     * freeing any mode slot and restoring Contract C1 (every triggered has a completed).
     * The Run is <strong>re-derived, never re-executed</strong> — no action runs (§3.10).</p>
     *
     * <p>The zombie set must be reconstructed by the caller from the immutable event log
     * only (the E91-1 reconstruction-source pin) — never from a windowed/in-memory map. The
     * caller passes the reconstructed descriptors; this method publishes their terminal
     * completions.</p>
     *
     * @param zombies the reconstructed zombie descriptors, never {@code null}
     * @return the number of zombie Runs finalized, always {@code >= 0}
     */
    int finalizeZombieRuns(List<ZombieRun> zombies);

    /**
     * A reconstructed in-flight Run from a prior process, re-derived from the immutable
     * event log for REPLAY&rarr;LIVE zombie finalization (Doc 07 §3.10).
     *
     * <p>All identifiers are the flattened forms recovered from the zombie's
     * {@code automation_triggered} envelope and payload, so the synthesized
     * {@code automation_completed} lands in the same causal chain with the same actor
     * attribution.</p>
     *
     * @param runId         the zombie Run's identifier (bare ULID), never {@code null}
     * @param automationId  the zombie Run's automation, never {@code null}
     * @param correlationId the causal chain's correlation id (bare ULID), never
     *                      {@code null}
     * @param causationId   the causing event's id (the zombie's {@code automation_triggered}
     *                      event id, bare ULID), never {@code null}
     * @param actorRef      the envelope actor for the synthesized completion (the
     *                      automation's id, per B2-C8), never {@code null}
     * @param eventTime     the real-world occurrence time inherited onto the completion;
     *                      {@code null} when the source had no reliable clock
     */
    record ZombieRun(
            Ulid runId,
            AutomationId automationId,
            Ulid correlationId,
            Ulid causationId,
            Ulid actorRef,
            Instant eventTime
    ) {

        /**
         * Validates that all non-nullable fields are non-null. {@code eventTime} is
         * nullable.
         *
         * @throws NullPointerException if any field other than {@code eventTime} is
         *                              {@code null}
         */
        public ZombieRun {
            Objects.requireNonNull(runId, "runId must not be null");
            Objects.requireNonNull(automationId, "automationId must not be null");
            Objects.requireNonNull(correlationId, "correlationId must not be null");
            Objects.requireNonNull(causationId, "causationId must not be null");
            Objects.requireNonNull(actorRef, "actorRef must not be null");
        }
    }
}
