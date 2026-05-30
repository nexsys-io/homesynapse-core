/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

import com.homesynapse.event.EventEnvelope;

import java.util.function.Consumer;

/**
 * Per-event-type handler for {@link DispatchingProjectionAdvancer} (REC-28
 * mod B — package-private, no {@code .handlers} subpackage, one flat package).
 *
 * <p>A handler decides how an envelope of a given event type is forwarded to the
 * projection's {@code processor} during a bounded read window. It dispatches the
 * <b>read/forward</b> concern only — <b>never</b> derivation or publication.
 * Plan §4.2 keeps derivation in the {@link DerivationRule} (the publishing seam),
 * because the LIVE {@code onEvent} path does not use the advancer at all; moving
 * derivation here would silently skip it on LIVE.</p>
 *
 * <p>The amendment-free M4.0b-1 slice ships a single forwarding implementation
 * ({@code DispatchingProjectionAdvancer.ForwardingHandler}) wired for every event
 * type — see {@link DispatchingProjectionAdvancer#withDefaultHandlers}. The map
 * is the seam where M4.0b-2 / Workstream-B can attach type-specific read/forward
 * handlers without touching the advancer's loop.</p>
 *
 * @see DispatchingProjectionAdvancer
 */
@FunctionalInterface
interface EnvelopeHandler {

    /**
     * Handles one envelope by forwarding it (in whole or in part) to the
     * projection's per-event processor.
     *
     * <p>Implementations MUST NOT call {@code EventPublisher.publish()} or open
     * writes — the processor runs inside the advancer's read transaction
     * (AMD-41 §3.2.1). Implementations MUST NOT alter the advancer's
     * {@code globalPosition}/cursor accounting; the advancer counts every
     * dispatched envelope toward {@code eventsProcessed} regardless of what the
     * handler does.</p>
     *
     * @param envelope  the inbound envelope being dispatched; never {@code null}
     * @param processor the projection's per-event consumer; never {@code null}
     */
    void handle(EventEnvelope envelope, Consumer<EventEnvelope> processor);
}
