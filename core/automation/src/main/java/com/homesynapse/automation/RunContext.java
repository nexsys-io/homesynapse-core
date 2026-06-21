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
 * <p>The {@link #causalChain()} is the Run's immutable causal lineage (AMD-91). It is
 * {@link RunCausalChain#root()} (empty, depth 0) for user/device/time-initiated Runs; a
 * cascade Run inherits its parent's chain extended by the parent link, so
 * {@code causalChain().depth()} equals {@code parent.causalChain().depth() + 1}. Maximum
 * depth is governed by {@code automation.max_cascade_depth} (default 8, range 1&ndash;32);
 * exceeding it suppresses the Run and produces a {@code cascade_depth_exceeded} DIAGNOSTIC
 * event.</p>
 *
 * <p><b>Governing model: AMD-91 (supersedes AMD-04).</b> AMD-91 keeps the depth-limiting
 * semantics above <em>unchanged</em> (same {@code automation.max_cascade_depth} key, same
 * default 8 / range 1&ndash;32, same {@code cascade_depth_exceeded} diagnostic), but
 * <em>upgrades</em> cycle detection: the governing lineage is this {@link RunCausalChain}
 * (an ordered list of {@code (RunId, AutomationId)} chain links) and loop detection is the
 * deterministic <em>chain-membership</em> test
 * ({@link RunCausalChain#containsAutomation(com.homesynapse.platform.identity.AutomationId)})
 * emitting a distinct {@code cascade_loop_detected} DIAGNOSTIC &mdash; replacing AMD-04's
 * windowed, evictable {@code (correlation_id, automation_id)} suppression set
 * (AMD-91-INV-01: suppression is a pure function of the Run's causal chain plus config,
 * with no window/eviction/restart-sensitive state). The chain is automation-resident and
 * crosses the event boundary only flattened (AMD-91-INV-02 / AMD-92-INV-01).</p>
 *
 * <p>Defined in Doc 07 §8.2; cascade model AMD-91 (RATIFIED 2026-06-12, supersedes AMD-04).</p>
 *
 * @param runId                  the unique identifier for this Run, never {@code null}
 * @param automationId           the automation being executed, never {@code null}
 * @param triggeringEventId      the event that triggered this Run, never {@code null}
 * @param matchedTriggers        indices of the triggers that matched, unmodifiable,
 *                               never {@code null}
 * @param resolvedTargets        resolved entity sets keyed by selector label or position,
 *                               unmodifiable, never {@code null}
 * @param definitionHash         SHA-256 hex of the automation definition, never {@code null}
 * @param causalChain            the Run's immutable causal lineage (AMD-91); the authority
 *                               for cascade depth ({@code causalChain.depth()}) and cycle
 *                               suppression. {@link RunCausalChain#root()} for root Runs;
 *                               never {@code null}
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
        RunCausalChain causalChain,
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
        Objects.requireNonNull(causalChain, "causalChain must not be null");
        matchedTriggers = List.copyOf(matchedTriggers);
        resolvedTargets = Map.copyOf(resolvedTargets);
    }
}
