/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event.bus;

/**
 * Point-in-time introspection snapshot of a subscriber's state (PLAN §4.3).
 *
 * <p>Returned by {@link EventBus#subscriberInfo(String)} and
 * {@link EventBus#subscribers()}. This is a read-only projection of the
 * subscriber's runtime state, separate from the registration descriptor
 * {@link SubscriberInfo}.</p>
 *
 * @param subscriberId the subscriber's stable identifier
 * @param mode         the current lifecycle mode
 * @param checkpoint   the last delivered global position; 0 if never delivered
 * @param dlqDepth     current DLQ size (in-memory entries)
 * @param crashCount   crashes within the current rolling 10-minute window
 */
public record SubscriberSnapshot(
    String subscriberId,
    SubscriberMode mode,
    long checkpoint,
    int dlqDepth,
    int crashCount
) {

    /**
     * Validates all snapshot fields.
     *
     * @throws NullPointerException if {@code subscriberId} or {@code mode} is {@code null}
     */
    public SubscriberSnapshot {
        java.util.Objects.requireNonNull(subscriberId, "subscriberId must not be null");
        java.util.Objects.requireNonNull(mode, "mode must not be null");
    }
}
