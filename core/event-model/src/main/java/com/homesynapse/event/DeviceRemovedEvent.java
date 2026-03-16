/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import java.util.Objects;

/**
 * Event emitted when a device is removed from the system.
 * <p>
 * Priority: NORMAL
 * Doc 01 §4.3
 */
public record DeviceRemovedEvent(
        String reason
) implements DomainEvent {

    /**
     * Constructs a DeviceRemovedEvent with validation.
     *
     * @param reason the reason for removal, not null or blank
     */
    public DeviceRemovedEvent {
        Objects.requireNonNull(reason, "reason cannot be null");
        if (reason.isBlank()) {
            throw new IllegalArgumentException("reason cannot be blank");
        }
    }
}
