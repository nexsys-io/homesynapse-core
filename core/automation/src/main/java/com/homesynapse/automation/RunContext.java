/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.homesynapse.event.EventId;
import com.homesynapse.platform.identity.AutomationId;
import com.homesynapse.platform.identity.EntityId;

/**
 * Execution context carried on a Run's virtual thread throughout its lifecycle.
 *
 * <p>The context is created at Run initiation and provides all the resolved state
 * needed for action execution. All entity selectors are resolved once at trigger
 * time (C4) and captured in {@link #resolvedTargets()} — no re-resolution occurs
 * during action execution.</p>
 *
 * <h2>Definition Hash</h2>
 *
 * <p>The {@link #definitionHash()} is a SHA-256 hex string computed from the automation
 * definition at Run initiation time. During REPLAY, the hash is compared against the
 * current definition to detect changes that would invalidate replay fidelity (§3.7).</p>
 *
 * <h2>Cascade Governance</h2>
 *
 * <p>The {@link #cascadeDepth()} is 0 for user/device-initiated Runs. For cascade Runs
 * (triggered by events emitted from other automations), the depth equals
 * {@code parent_run.cascadeDepth + 1}. Maximum depth is governed by
 * {@code automation.max_cascade_depth} (default 8, range 1&ndash;32). Exceeding the
 * maximum produces a {@code cascade_depth_exceeded} DIAGNOSTIC event.</p>
 *
 * <p>Defined in Doc 07 §8.2.</p>
 *
 * @param runId                  the unique identifier for this Run, never {@code null}
 * @param automationId           the automation being executed, never {@code null}
 * @param triggeringEventId      the event that triggered this Run, never {@code null}
 * @param matchedTriggers        indices of the triggers that matched, unmodifiable,
 *                               never {@code null}
 * @param resolvedTargets        resolved entity sets keyed by selector label or position,
 *                               unmodifiable, never {@code null}
 * @param definitionHash         SHA-256 hex of the automation definition, never {@code null}
 * @param cascadeDepth           cascade depth (0 for root Runs)
 * @param stateSnapshotPosition  the {@code viewPosition} from the
 *                               {@link com.homesynapse.state.StateSnapshot} captured at
 *                               trigger time
 * @see RunManager
 * @see ActionExecutor
 */
public record RunContext(
        RunId runId,
        AutomationId automationId,
        EventId triggeringEventId,
        List<Integer> matchedTriggers,
        Map<String, Set<EntityId>> resolvedTargets,
        String definitionHash,
        int cascadeDepth,
        long stateSnapshotPosition
) {

    /**
     * Validates non-null fields and makes collections unmodifiable.
     *
     * @throws NullPointerException if any non-nullable field is {@code null}
     */
    public RunContext {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(automationId, "automationId must not be null");
        Objects.requireNonNull(triggeringEventId, "triggeringEventId must not be null");
        Objects.requireNonNull(matchedTriggers, "matchedTriggers must not be null");
        Objects.requireNonNull(resolvedTargets, "resolvedTargets must not be null");
        Objects.requireNonNull(definitionHash, "definitionHash must not be null");
        matchedTriggers = List.copyOf(matchedTriggers);
        resolvedTargets = Map.copyOf(resolvedTargets);
    }
}
