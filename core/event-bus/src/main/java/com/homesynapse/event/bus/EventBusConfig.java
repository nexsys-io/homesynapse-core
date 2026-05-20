/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event.bus;

/**
 * Operator-tunable configuration for {@link InProcessEventBus} (M3.6b,
 * audit findings D1-07 and D4-09).
 *
 * <p>This record bundles the bus parameters that need to vary across
 * deployment tiers without changing wiring code. The default constant
 * {@link #HOME_DEFAULT} reproduces the original M3.4b behaviour exactly —
 * a 10,000-entry replay window queue and a publisher-blocked threshold of
 * 5,000 writer-queue entries — so existing callers that opt into the
 * default see no behavioural change.</p>
 *
 * <p>Background:</p>
 * <ul>
 *   <li><strong>D1-07:</strong> the publisher-blocked depth threshold was
 *       previously a hard-coded {@code static final int = 5000} on
 *       {@code InProcessEventBus}, blocking per-tier tuning.</li>
 *   <li><strong>D4-09:</strong> the {@link ReplayWindowQueue} capacity was
 *       previously a hard-coded {@code static final int = 10_000}, which on
 *       Enterprise-tier deployments with high throughput during REPLAY can
 *       trigger an overflow restart loop. Promoting the bound to a
 *       constructor parameter unblocks per-deployment tuning without
 *       changing the default for HOME-tier deployments.</li>
 * </ul>
 *
 * <p>Fields are validated by the compact constructor — both must be
 * {@code >= 1}. Capacity tuning is a deployment-time decision; the bus
 * does not adapt at runtime.</p>
 *
 * @param replayQueueCapacity           maximum number of buffered global
 *                                      positions in a subscriber's
 *                                      {@link ReplayWindowQueue} before
 *                                      overflow triggers a REPLAY restart
 *                                      from the persisted checkpoint
 *                                      (AMD-42 §3.4.2); must be {@code >= 1}
 * @param publisherBlockedDepthThreshold writer-queue depth at which
 *                                      {@link InProcessEventBus#notifyEvent}
 *                                      increments the publisher-blocked
 *                                      counter (AMD-43 §3.6.2,
 *                                      INV-BUS-02 — record only, never
 *                                      block); must be {@code >= 1}
 * @see InProcessEventBus
 * @see ReplayWindowQueue
 */
public record EventBusConfig(
        int replayQueueCapacity,
        int publisherBlockedDepthThreshold) {

    /**
     * MVP default — reproduces the original M3.4b behaviour exactly.
     *
     * <p>{@code replayQueueCapacity = 10_000} matches the previous
     * {@code ReplayWindowQueue.MAX_CAPACITY} constant. {@code
     * publisherBlockedDepthThreshold = 5_000} matches the previous
     * {@code InProcessEventBus.PUBLISHER_BLOCKED_DEPTH_THRESHOLD} constant.
     * Callers using this constant observe no behavioural change relative to
     * M3.4b.</p>
     */
    public static final EventBusConfig HOME_DEFAULT =
            new EventBusConfig(10_000, 5_000);

    /**
     * Validates that both fields are at least 1.
     *
     * @throws IllegalArgumentException if either field is less than 1
     */
    public EventBusConfig {
        if (replayQueueCapacity < 1) {
            throw new IllegalArgumentException(
                    "replayQueueCapacity must be >= 1, got: " + replayQueueCapacity);
        }
        if (publisherBlockedDepthThreshold < 1) {
            throw new IllegalArgumentException(
                    "publisherBlockedDepthThreshold must be >= 1, got: "
                            + publisherBlockedDepthThreshold);
        }
    }
}
