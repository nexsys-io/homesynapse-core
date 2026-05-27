/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event.bus;

import com.homesynapse.event.EventEnvelope;

/**
 * Runtime callback interface for event delivery (AMD-42 §3.4).
 *
 * <p>Implementations receive events via {@link #onEvent(EventEnvelope)} and
 * an optional caught-up notification via {@link #onCaughtUp()}. The bus's
 * {@link SubscriberSupervisor} wraps all invocations in exception handling.</p>
 *
 * <p><strong>Thread safety:</strong> {@code onEvent} is called on the subscriber's
 * dedicated virtual thread. Implementations need not be thread-safe unless they
 * share state with other components.</p>
 */
public interface Subscriber {

    /**
     * Called when a matching event is available for processing.
     *
     * @param event the event envelope to process; never {@code null}
     */
    void onEvent(EventEnvelope event);

    /**
     * Called exactly once per process lifetime per subscriber when the
     * TRANSITION → LIVE mode switch completes (AMD-42 §3.4.3).
     *
     * <p>The default implementation is a no-op. Subscribers that need to
     * perform post-catch-up initialization (e.g., enabling query serving)
     * override this method.</p>
     */
    default void onCaughtUp() { /* no-op */ }

    /**
     * Lifecycle callback invoked by the bus after every successful mode
     * transition for this subscriber. Subscribers that track their own mode
     * (e.g., {@code StateProjection}) override this to keep their internal
     * state in sync with the bus's authoritative FSM.
     *
     * <p>Called on the subscriber's dedicated virtual thread, IMMEDIATELY
     * after a successful CAS on the bus's runtime.mode. Failed CAS attempts
     * do NOT trigger this callback.</p>
     *
     * <p>Implementations MUST be fast and non-blocking — some bus-side call
     * sites hold internal locks.</p>
     *
     * @param mode the new mode the subscriber has transitioned into
     * @since M3.7 fix round 4 — closes the H5 cross-module gap (the
     *        Subscriber interface was missing this lifecycle hook in
     *        Phase 2; surfaced at M3.7 integration testing)
     */
    default void setMode(SubscriberMode mode) {
        // No-op by default. Mode-aware subscribers override.
    }
}
