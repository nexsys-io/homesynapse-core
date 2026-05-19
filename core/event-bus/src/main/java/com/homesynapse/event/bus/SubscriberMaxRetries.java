/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event.bus;

/**
 * Per-subscriber maximum-retry cap, governing how many delivery attempts a
 * supervisor makes before parking an event in the dead-letter queue (AMD-36).
 *
 * <p>The default cap of {@code 5} matches AMD-36's tolerance budget — six total
 * delivery attempts (1 initial + 5 retries) before the event is treated as a
 * poison message and parked. The cap is exposed as a typed wrapper rather than
 * a bare {@code int} so call sites at the bus-config boundary cannot accept
 * meaningless zero or negative values, and so future evolutions
 * (per-event-type caps, per-priority caps) have a place to land.</p>
 *
 * @param value the maximum retry count; must be at least 1
 */
public record SubscriberMaxRetries(int value) {

    /**
     * Default retry cap per AMD-36 (five retries after the initial delivery,
     * for six total delivery attempts).
     */
    public static final SubscriberMaxRetries DEFAULT = new SubscriberMaxRetries(5);

    /**
     * Compact constructor enforcing {@code value >= 1}.
     *
     * @throws IllegalArgumentException if {@code value < 1}
     */
    public SubscriberMaxRetries {
        if (value < 1) {
            throw new IllegalArgumentException(
                    "maxRetries must be >= 1, got " + value);
        }
    }
}
