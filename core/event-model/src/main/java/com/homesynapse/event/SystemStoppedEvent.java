/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

package com.homesynapse.event;

import java.util.Objects;

/**
 * Event emitted when the HomeSynapse process shuts down.
 * <p>
 * Priority: CRITICAL
 * Doc 01 §4.3
 */
public record SystemStoppedEvent(
        String reason,
        boolean cleanShutdown
) implements DomainEvent {

    /**
     * Constructs a SystemStoppedEvent with validation.
     *
     * @param reason         the reason for shutdown, not null or blank
     * @param cleanShutdown  whether the shutdown was clean
     */
    public SystemStoppedEvent {
        Objects.requireNonNull(reason, "reason cannot be null");
        if (reason.isBlank()) {
            throw new IllegalArgumentException("reason cannot be blank");
        }
    }
}
