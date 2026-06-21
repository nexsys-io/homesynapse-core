/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.homesynapse.platform.identity.AutomationId;

/**
 * The immutable causal lineage of an automation Run — the authority for cascade depth
 * and cycle suppression, and the type the explainability projection (Doc 16 §3.3) reads
 * to render why a Run fired (or why a candidate Run did not).
 *
 * <p>Governing model: AMD-91 (RATIFIED 2026-06-12, supersedes AMD-04). A chain is an
 * ordered list of {@link ChainLink}s, one per ancestor Run in the causal chain that led
 * to this Run. Depth is <em>derived</em> from the chain length — it is never stored
 * separately, so {@link #depth()} equals the old AMD-04 {@code cascadeDepth} int by
 * construction (root = 0, each cascade hop = parent + 1).</p>
 *
 * <p>Cascade governance is a pure function of the chain plus configuration, with no
 * windowed, evictable, or restart-sensitive state (AMD-91-INV-01): depth limiting compares
 * {@link #depth()} against {@code automation.max_cascade_depth}, and cycle detection is the
 * deterministic chain-membership test {@link #containsAutomation(AutomationId)} — which
 * replaces AMD-04's windowed {@code (correlation_id, automation_id)} suppression set.</p>
 *
 * <p><strong>Residency (AMD-91-INV-02 / AMD-92-INV-01).</strong> This type is
 * automation-resident and <em>never</em> appears in an event payload. It crosses the
 * event boundary only as flattened projections — {@link #depth()} as an {@code int}
 * {@code cascadeDepth}, and the ancestors' {@link AutomationId}s as a
 * {@code List<AutomationId>} cycle path. Referencing it from a {@code com.homesynapse.event}
 * record would invert the JPMS edge to {@code event -> automation} (a hard compile cycle).</p>
 *
 * <p>Value-based equality (a record); immutable after construction. Bounded by the depth
 * ceiling (&le; 32 links), so allocation in {@link #extend(ChainLink)} is trivial.</p>
 *
 * @param ancestors the ordered ancestor chain links, unmodifiable (defensively copied),
 *                  never {@code null}; empty for a root Run
 * @see RunContext
 * @see RunManager
 */
public record RunCausalChain(List<ChainLink> ancestors) {

    /**
     * One link in a Run's causal chain: the ancestor Run and the automation it ran.
     *
     * <p>The {@link #automationId()} is what the cycle test reads — a bare {@code RunId}
     * list could not answer "is automation A already in this chain" without a registry
     * lookup.</p>
     *
     * @param runId        the ancestor Run's identifier, never {@code null}
     * @param automationId the automation that ancestor Run executed, never {@code null}
     */
    public record ChainLink(RunId runId, AutomationId automationId) {

        /**
         * Validates that both fields are non-null.
         *
         * @throws NullPointerException if {@code runId} or {@code automationId} is
         *                              {@code null}
         */
        public ChainLink {
            Objects.requireNonNull(runId, "runId must not be null");
            Objects.requireNonNull(automationId, "automationId must not be null");
        }
    }

    /**
     * Validates non-null and defensively copies {@code ancestors} to an immutable list.
     *
     * @throws NullPointerException if {@code ancestors} (or any element) is {@code null}
     */
    public RunCausalChain {
        Objects.requireNonNull(ancestors, "ancestors must not be null");
        ancestors = List.copyOf(ancestors);
    }

    /**
     * Returns the chain for a root Run: no ancestors, {@link #depth()} 0 — preserving the
     * old AMD-04 {@code cascadeDepth == 0} root semantics.
     *
     * @return a root causal chain, never {@code null}
     */
    public static RunCausalChain root() {
        return new RunCausalChain(List.of());
    }

    /**
     * Returns a new chain with {@code parent} appended; this chain is unchanged.
     *
     * @param parent the ancestor link to append, never {@code null}
     * @return a new {@code RunCausalChain} one link deeper, never {@code null}
     * @throws NullPointerException if {@code parent} is {@code null}
     */
    public RunCausalChain extend(ChainLink parent) {
        Objects.requireNonNull(parent, "parent must not be null");
        var next = new ArrayList<ChainLink>(ancestors);
        next.add(parent);
        return new RunCausalChain(next);
    }

    /**
     * Returns the cascade depth — the number of ancestor links. Derived, not stored;
     * equals the old AMD-04 {@code cascadeDepth} int by construction.
     *
     * @return the depth, always {@code >= 0}
     */
    public int depth() {
        return ancestors.size();
    }

    /**
     * Returns whether {@code id} already appears as the automation of any ancestor link —
     * the deterministic cycle-detection test (AMD-91 §2.3.2).
     *
     * @param id the automation to test for membership, never {@code null}
     * @return {@code true} if {@code id} is already in the lineage
     * @throws NullPointerException if {@code id} is {@code null}
     */
    public boolean containsAutomation(AutomationId id) {
        Objects.requireNonNull(id, "id must not be null");
        return ancestors.stream().anyMatch(link -> link.automationId().equals(id));
    }
}
