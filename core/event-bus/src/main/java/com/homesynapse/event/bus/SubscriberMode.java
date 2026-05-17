/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event.bus;

/**
 * Lifecycle mode of a registered subscriber (AMD-42 §3.4.1).
 *
 * <p>Transitions are atomic via {@code AtomicReference<SubscriberMode>}
 * with CAS-based updates (INV-SUB-ISO-04).</p>
 */
public enum SubscriberMode {

    /** Initial state. No reads, no writes. */
    COLD,

    /** Catching up from checkpoint. Bounded-window reads (500 rows). */
    REPLAY,

    /** Draining the replay window queue before going live. */
    TRANSITION,

    /** Steady-state. Woken by {@code notifyEvent}. */
    LIVE,

    /** Circuit breaker tripped. No deliveries until {@code resume()}. */
    SUSPENDED
}
