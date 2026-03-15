/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

package com.homesynapse.event;

import java.util.Objects;

/**
 * Event emitted when device availability status changes.
 * <p>
 * Valid status values: "online", "offline", "unknown".
 * Priority: CRITICAL when transitioning to offline, NORMAL when to online.
 * Doc 01 §4.3
 */
public record AvailabilityChangedEvent(
        String previousStatus,
        String newStatus
) implements DomainEvent {

    /**
     * Constructs an AvailabilityChangedEvent with validation.
     *
     * @param previousStatus the previous availability status, not null or blank
     * @param newStatus      the new availability status, not null or blank
     */
    public AvailabilityChangedEvent {
        Objects.requireNonNull(previousStatus, "previousStatus cannot be null");
        if (previousStatus.isBlank()) {
            throw new IllegalArgumentException("previousStatus cannot be blank");
        }
        Objects.requireNonNull(newStatus, "newStatus cannot be null");
        if (newStatus.isBlank()) {
            throw new IllegalArgumentException("newStatus cannot be blank");
        }
    }
}
