/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event.bus;

import java.util.Objects;

/**
 * Immutable descriptor for a subscriber registration with the {@link EventBus}.
 *
 * <p>Each {@code SubscriberInfo} captures the metadata needed to manage a subscriber's
 * lifecycle within the bus: a stable identifier for checkpoint persistence, a filter
 * that governs which events trigger notification, and a flag indicating whether the
 * subscriber is exempt from backpressure coalescing.</p>
 *
 * <p>The {@link #subscriberId()} serves as the primary key in the
 * {@code subscriber_checkpoints} table (Doc 01 §4.2) and must be stable across
 * application restarts for checkpoint-based resumption to work correctly.</p>
 *
 * @param subscriberId   a stable string identifier for the subscriber (e.g.,
 *                       {@code "state_projection"}, {@code "automation_engine"}).
 *                       Used as the primary key in the subscriber_checkpoints table.
 *                       Never {@code null} or blank.
 * @param filter         the {@link SubscriptionFilter} for bus-side event matching.
 *                       Never {@code null}.
 * @param coalesceExempt {@code true} if this subscriber must receive every event
 *                       individually, even under backpressure (Doc 01 §3.6). The State
 *                       Projection and Pending Command Ledger are coalescing-exempt
 *                       because skipping intermediate events would cause missed state
 *                       transitions or missed confirmation matches. Most subscribers
 *                       should use {@code false}.
 * @param atomicCheckpoint {@code true} if this subscriber's checkpoint is written
 *                       atomically with its materialized view (AMD-45 §2.2 Option A).
 *                       When {@code true}, the bus SKIPS the per-delivery
 *                       {@code subscriber_checkpoints} write — the subscriber (e.g. the
 *                       State Projection) writes the coupled subscriber+view checkpoint
 *                       on its own policy cadence via {@code AtomicCheckpointSink},
 *                       closing the crash window AMD-45 §1 describes. Most subscribers
 *                       use {@code false} and rely on the bus's per-delivery checkpoint.
 *                       Mirrors the established per-subscriber-flag pattern of
 *                       {@code coalesceExempt}.
 * @see EventBus#subscribe(SubscriberInfo)
 * @see SubscriptionFilter
 * @see CheckpointStore
 * @see <a href="Doc 01 §3.4">Subscription Model</a>
 * @see <a href="Doc 01 §3.6">Backpressure and Coalescing</a>
 */
public record SubscriberInfo(
        String subscriberId,
        SubscriptionFilter filter,
        boolean coalesceExempt,
        boolean atomicCheckpoint
) {

    /**
     * Validates all subscriber registration metadata.
     *
     * @throws NullPointerException     if {@code subscriberId} or {@code filter} is
     *                                  {@code null}
     * @throws IllegalArgumentException if {@code subscriberId} is blank
     */
    public SubscriberInfo {
        Objects.requireNonNull(subscriberId, "subscriberId must not be null");
        Objects.requireNonNull(filter, "filter must not be null");
        if (subscriberId.isBlank()) {
            throw new IllegalArgumentException("subscriberId must not be blank");
        }
    }

    /**
     * Backward-compatible convenience constructor for subscribers that use the
     * bus's per-delivery checkpoint (the common case). Delegates to the
     * canonical constructor with {@code atomicCheckpoint = false} (AMD-45 §2.2
     * — existing subscribers retain pre-AMD-45 behavior).
     *
     * @param subscriberId   stable subscriber identifier; never {@code null} or blank
     * @param filter         the subscription filter; never {@code null}
     * @param coalesceExempt whether this subscriber is exempt from coalescing
     */
    public SubscriberInfo(String subscriberId, SubscriptionFilter filter,
                          boolean coalesceExempt) {
        this(subscriberId, filter, coalesceExempt, false);
    }
}
