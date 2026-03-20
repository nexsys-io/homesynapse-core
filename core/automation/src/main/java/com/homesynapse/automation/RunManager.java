/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.homesynapse.event.EventEnvelope;
import com.homesynapse.platform.identity.AutomationId;
import com.homesynapse.platform.identity.EntityId;

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
     * @param automation      the automation to run, never {@code null}
     * @param triggeringEvent the event that triggered this Run, never {@code null}
     * @param matchedTriggers indices of the triggers that matched, never {@code null}
     * @param resolvedTargets resolved entity sets keyed by selector label,
     *                        never {@code null}
     * @param cascadeDepth    the cascade depth (0 for root Runs)
     * @return the Run ID if initiated, or empty if the mode rejected the Run
     */
    Optional<RunId> initiateRun(
            AutomationDefinition automation,
            EventEnvelope triggeringEvent,
            List<Integer> matchedTriggers,
            Map<String, Set<EntityId>> resolvedTargets,
            int cascadeDepth);

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
}
