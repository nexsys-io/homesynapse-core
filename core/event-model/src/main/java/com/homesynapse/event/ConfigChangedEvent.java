/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

package com.homesynapse.event;

import java.util.Objects;

/**
 * Event emitted when configuration is modified.
 * <p>
 * previousValue may be null for new configuration keys.
 * Priority: NORMAL
 * Doc 01 §4.3
 */
public record ConfigChangedEvent(
        String configPath,
        String previousValue,
        String newValue
) implements DomainEvent {

    /**
     * Constructs a ConfigChangedEvent with validation.
     *
     * @param configPath    the configuration path, not null or blank
     * @param previousValue the previous configuration value, may be null
     * @param newValue      the new configuration value, not null
     */
    public ConfigChangedEvent {
        Objects.requireNonNull(configPath, "configPath cannot be null");
        if (configPath.isBlank()) {
            throw new IllegalArgumentException("configPath cannot be blank");
        }
        Objects.requireNonNull(newValue, "newValue cannot be null");
    }
}
