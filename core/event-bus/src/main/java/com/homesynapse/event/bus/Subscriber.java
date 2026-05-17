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
}
