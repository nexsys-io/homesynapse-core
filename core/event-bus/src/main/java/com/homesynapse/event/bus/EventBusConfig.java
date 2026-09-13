/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event.bus;

import java.time.Duration;
import java.util.Objects;

/**
 * Operator-tunable configuration for {@link InProcessEventBus} (M3.6b,
 * audit findings D1-07 and D4-09; BUS-ORDER-1 adds the two LIVE read-forward
 * fields of AMD-101 §3.5).
 *
 * <p>This record bundles the bus parameters that need to vary across
 * deployment tiers without changing wiring code. The default constant
 * {@link #HOME_DEFAULT} reproduces the original M3.4b behaviour exactly for
 * the two M3.6b fields — a 10,000-entry replay window queue and a
 * publisher-blocked threshold of 5,000 writer-queue entries — and carries the
 * AMD-101 defaults for the two LIVE fields: 64 events per page read and a
 * 250 ms idle tick.</p>
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
 *   <li><strong>BUS-ORDER-1 (AMD-101 §2, 2026-09-12):</strong> the LIVE loop
 *       delivers by reading the store forward from the subscriber's in-memory
 *       cursor, in pages of {@code liveReadBatch}; the notification is only a
 *       wake hint, and {@code liveIdleTick} bounds the cost of a lost wake to
 *       one idle park followed by one page read.</li>
 * </ul>
 *
 * <p>Fields are validated by the compact constructor. Capacity tuning is a
 * deployment-time decision; the bus does not adapt at runtime.</p>
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
 * @param liveReadBatch                 events per LIVE page read — the
 *                                      {@code maxCount} of the read-forward
 *                                      {@code readFrom(cursor, liveReadBatch)}
 *                                      (AMD-101 §3.5); must be {@code >= 1}
 * @param liveIdleTick                  the LIVE loop's bounded park between
 *                                      reads while no wake arrives — a lost
 *                                      wake costs at most one tick (AMD-101
 *                                      §2); must be {@code >= 1 ms}
 * @see InProcessEventBus
 * @see ReplayWindowQueue
 */
public record EventBusConfig(
        int replayQueueCapacity,
        int publisherBlockedDepthThreshold,
        int liveReadBatch,
        Duration liveIdleTick) {

    /** AMD-101 §3.5 default: events per LIVE page read. */
    public static final int DEFAULT_LIVE_READ_BATCH = 64;

    /** AMD-101 §3.5 default: the LIVE loop's bounded idle park. */
    public static final Duration DEFAULT_LIVE_IDLE_TICK = Duration.ofMillis(250);

    /** The floor of {@link #liveIdleTick}; declared before {@link #HOME_DEFAULT}, which validates against it. */
    private static final Duration MIN_LIVE_IDLE_TICK = Duration.ofMillis(1);

    /**
     * MVP default — reproduces the original M3.4b behaviour exactly for the
     * M3.6b fields and carries the AMD-101 §3.5 defaults for the LIVE fields.
     *
     * <p>{@code replayQueueCapacity = 10_000} matches the previous
     * {@code ReplayWindowQueue.MAX_CAPACITY} constant. {@code
     * publisherBlockedDepthThreshold = 5_000} matches the previous
     * {@code InProcessEventBus.PUBLISHER_BLOCKED_DEPTH_THRESHOLD} constant.
     * {@code liveReadBatch = 64} and {@code liveIdleTick = 250 ms} are Doc 01
     * §9's rows (AMD-101 §4).</p>
     */
    public static final EventBusConfig HOME_DEFAULT =
            new EventBusConfig(10_000, 5_000, DEFAULT_LIVE_READ_BATCH, DEFAULT_LIVE_IDLE_TICK);

    /**
     * Validates that the three counts are at least 1 and the tick at least 1 ms.
     *
     * @throws IllegalArgumentException if a count is less than 1 or the tick is
     *                                  shorter than 1 ms
     * @throws NullPointerException     if {@code liveIdleTick} is {@code null}
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
        if (liveReadBatch < 1) {
            throw new IllegalArgumentException(
                    "liveReadBatch must be >= 1, got: " + liveReadBatch);
        }
        Objects.requireNonNull(liveIdleTick, "liveIdleTick must not be null");
        if (liveIdleTick.compareTo(MIN_LIVE_IDLE_TICK) < 0) {
            throw new IllegalArgumentException(
                    "liveIdleTick must be >= 1 ms, got: " + liveIdleTick);
        }
    }

    /**
     * The two-field form (M3.6b): the replay-window capacity and the
     * publisher-blocked threshold, with the AMD-101 §3.5 defaults for the LIVE
     * read-forward fields — keeps the M3.6b construction sites source-compatible
     * (the {@code SubscriberInfo} 3-arg → 4-arg precedent).
     *
     * @param replayQueueCapacity            as on the canonical constructor
     * @param publisherBlockedDepthThreshold as on the canonical constructor
     */
    public EventBusConfig(int replayQueueCapacity, int publisherBlockedDepthThreshold) {
        this(replayQueueCapacity, publisherBlockedDepthThreshold,
                DEFAULT_LIVE_READ_BATCH, DEFAULT_LIVE_IDLE_TICK);
    }
}
