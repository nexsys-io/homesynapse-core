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
}
