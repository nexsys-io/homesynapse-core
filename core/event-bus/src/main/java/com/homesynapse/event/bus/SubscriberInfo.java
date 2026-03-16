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
 * @see EventBus#subscribe(SubscriberInfo)
 * @see SubscriptionFilter
 * @see CheckpointStore
 * @see <a href="Doc 01 §3.4">Subscription Model</a>
 * @see <a href="Doc 01 §3.6">Backpressure and Coalescing</a>
 */
public record SubscriberInfo(
        String subscriberId,
        SubscriptionFilter filter,
        boolean coalesceExempt
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
}
