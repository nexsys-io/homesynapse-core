/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import java.util.Objects;

/**
 * Event emitted when the HomeSynapse process starts.
 * <p>
 * Priority: CRITICAL
 * Doc 01 §4.3
 */
@EventType(EventTypes.SYSTEM_STARTED)
public record SystemStartedEvent(
        String version,
        long startupDurationMs
) implements DomainEvent {

    /**
     * Constructs a SystemStartedEvent with validation.
     *
     * @param version             the HomeSynapse version string, not null or blank
     * @param startupDurationMs   the startup duration in milliseconds, must be non-negative
     */
    public SystemStartedEvent {
        Objects.requireNonNull(version, "version cannot be null");
        if (version.isBlank()) {
            throw new IllegalArgumentException("version cannot be blank");
        }
        if (startupDurationMs < 0) {
            throw new IllegalArgumentException("startupDurationMs cannot be negative");
        }
    }
}
