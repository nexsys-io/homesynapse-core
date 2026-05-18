/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

import com.homesynapse.event.EventEnvelope;

import java.time.Clock;
import java.util.Objects;

/**
 * Read-only context passed to {@link DerivationRule#evaluate(DerivationContext)}.
 *
 * <p>Bundles the prior materialized state of the inbound event's subject entity
 * (or {@code null} when this is the first event for the entity) with the inbound
 * envelope and an injected {@link Clock} for any time-dependent derivation logic.</p>
 *
 * <h2>Determinism (INV-PROJ-01)</h2>
 *
 * <p>{@link DerivationRule} implementations MUST be deterministic — the same input
 * tuple {@code (priorState, envelope)} MUST produce the same set of derived drafts
 * regardless of when the rule runs. The {@link #clock()} is provided for rules that
 * need to record a derivation timestamp; rules MUST NOT branch on the clock's
 * current value because that would couple derivation to wall-clock time.</p>
 *
 * @param priorState the entity's materialized state before applying this inbound
 *                   event, or {@code null} when no prior state exists (first event
 *                   for the entity)
 * @param envelope   the inbound event envelope, never {@code null}
 * @param clock      the injected clock for time-dependent fields (e.g., derived
 *                   event timestamps); never {@code null}
 * @see DerivationRule
 * @see StateProjection
 */
public record DerivationContext(
        EntityState priorState,
        EventEnvelope envelope,
        Clock clock
) {

    /**
     * Validates the non-nullable components. {@code priorState} may be
     * {@code null}.
     *
     * @throws NullPointerException if {@code envelope} or {@code clock} is
     *                              {@code null}
     */
    public DerivationContext {
        Objects.requireNonNull(envelope, "envelope must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
    }
}
