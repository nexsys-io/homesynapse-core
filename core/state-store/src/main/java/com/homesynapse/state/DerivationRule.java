/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

import com.homesynapse.event.EventDraft;

import java.util.List;

/**
 * Strategy interface for deriving downstream events from inbound envelopes (DEC-M3-10).
 *
 * <p>The {@link StateProjection} delegates derivation to a {@code DerivationRule}
 * during the READ phase of each {@code onEvent} call (AMD-41 §3.2.1). The rule
 * inspects the {@link DerivationContext} (prior entity state + inbound envelope)
 * and returns zero or more {@link EventDraft} instances that the projection will
 * publish during the PUBLISH phase (LIVE mode only).</p>
 *
 * <h2>Contract</h2>
 *
 * <ul>
 *   <li>Implementations MUST NOT call {@code EventPublisher.publish()} — the
 *       projection handles publishing after the rule returns.</li>
 *   <li>Implementations MUST NOT mutate {@code StateStore} — the projection
 *       handles all state mutation in the STATE UPDATE phase.</li>
 *   <li>Implementations MUST be deterministic per INV-PROJ-01: the same
 *       {@code DerivationContext} MUST always produce the same drafts.</li>
 *   <li>The returned list MAY be empty when no derivation applies.</li>
 *   <li>Derived {@code EventDraft.eventTime} SHOULD inherit from the causing
 *       envelope or be {@code null} — never {@code Instant.now()}.</li>
 * </ul>
 *
 * @see DerivationContext
 * @see StateProjection
 */
@FunctionalInterface
public interface DerivationRule {

    /**
     * Evaluates the inbound event against prior state and returns zero or more
     * derived event drafts.
     *
     * @param context the read-only derivation context; never {@code null}
     * @return an immutable or defensively-copied list of derived drafts; may be
     *         empty, never {@code null}
     */
    List<EventDraft> evaluate(DerivationContext context);

    /**
     * Returns the production change-detect rule (REC-28, plan §4.2): on an
     * inbound {@code state_reported} whose value differs from the entity's prior
     * canonical value, it emits one derived {@code state_changed} draft;
     * otherwise it emits nothing. Comparison is string-valued (the existing
     * serialized form); typed comparison (REC-90) is M4.B3 scope.
     *
     * <p>This is the DEC-M3-16 gateway into the package-private
     * {@code ProductionDerivationRule} — it mirrors
     * {@link StateCheckpointSource#stub()} and
     * {@link StateQueryService#materialized}. The composition root wires the
     * returned rule into {@link StateProjection#create}; the projection publishes
     * the derived draft on LIVE (and re-derives without publishing on
     * REPLAY/TRANSITION, AMD-41 §3.2.2).</p>
     *
     * <p>The returned rule is stateless, deterministic (INV-PROJ-01), and safe
     * to share across threads.</p>
     *
     * @return the production derivation rule; never {@code null}
     */
    static DerivationRule production() {
        return new ProductionDerivationRule();
    }
}
