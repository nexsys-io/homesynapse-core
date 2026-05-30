/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

import com.homesynapse.event.EventEnvelope;

import java.util.Objects;

/**
 * Read-only context passed to {@link DerivationRule#evaluate(DerivationContext)}.
 *
 * <p>Bundles the prior materialized state of the inbound event's subject entity
 * (or {@code null} when this is the first event for the entity) with the inbound
 * envelope. Nothing else: there is deliberately no clock.</p>
 *
 * <h2>Determinism (INV-PROJ-01, AMD-50 §2.4 / AMD-50-INV-03)</h2>
 *
 * <p>{@link DerivationRule} implementations MUST be deterministic — the same input
 * tuple {@code (priorState, envelope)} MUST produce the same set of derived drafts
 * regardless of when the rule runs. AMD-50 §2.4 removes the formerly-injected
 * {@code Clock} from this context: a derived {@code EventDraft.eventTime} inherits
 * from the causing envelope (never {@code Instant.now()}) and ingest-time stamping
 * is the publisher's job, so the rule has no legitimate use for a clock. Removing
 * it makes the determinism contract airtight <em>by construction</em> — there is
 * no clock value to branch on, so the reconciliation backfill (AMD-50 §2.1) that
 * re-executes the rule during a replay-from-zero rebuild cannot diverge from the
 * original live derivation.</p>
 *
 * @param priorState the entity's materialized state before applying this inbound
 *                   event, or {@code null} when no prior state exists (first event
 *                   for the entity)
 * @param envelope   the inbound event envelope, never {@code null}
 * @see DerivationRule
 * @see StateProjection
 */
public record DerivationContext(
        EntityState priorState,
        EventEnvelope envelope
) {

    /**
     * Validates the non-nullable components. {@code priorState} may be
     * {@code null}.
     *
     * @throws NullPointerException if {@code envelope} is {@code null}
     */
    public DerivationContext {
        Objects.requireNonNull(envelope, "envelope must not be null");
    }
}
