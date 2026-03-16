/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import java.util.Objects;

/**
 * Event emitted for configuration validation issues that caused a key to revert to schema default at startup.
 * <p>
 * Priority: DIAGNOSTIC
 * Doc 01 §4.3
 */
public record ConfigErrorEvent(
        String path,
        String severity,
        String message,
        String appliedDefault
) implements DomainEvent {

    /**
     * Constructs a ConfigErrorEvent with validation.
     *
     * @param path           the configuration path, not null or blank
     * @param severity       the error severity, not null or blank
     * @param message        the error message, not null or blank
     * @param appliedDefault the applied default value, not null
     */
    public ConfigErrorEvent {
        Objects.requireNonNull(path, "path cannot be null");
        if (path.isBlank()) {
            throw new IllegalArgumentException("path cannot be blank");
        }
        Objects.requireNonNull(severity, "severity cannot be null");
        if (severity.isBlank()) {
            throw new IllegalArgumentException("severity cannot be blank");
        }
        Objects.requireNonNull(message, "message cannot be null");
        if (message.isBlank()) {
            throw new IllegalArgumentException("message cannot be blank");
        }
        Objects.requireNonNull(appliedDefault, "appliedDefault cannot be null");
    }
}
