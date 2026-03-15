/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

package com.homesynapse.event;

import java.util.Objects;

/**
 * Event emitted when storage pressure level transitions.
 * <p>
 * Valid level values: "HEALTHY", "WARNING", "CRITICAL", "EMERGENCY".
 * Priority: NORMAL
 * Doc 01 §4.3
 */
public record StoragePressureChangedEvent(
        String oldLevel,
        String newLevel,
        long diskUsageBytes,
        long thresholdBytes
) implements DomainEvent {

    /**
     * Constructs a StoragePressureChangedEvent with validation.
     *
     * @param oldLevel         the previous storage pressure level, not null or blank
     * @param newLevel         the new storage pressure level, not null or blank
     * @param diskUsageBytes   the current disk usage in bytes, must be non-negative
     * @param thresholdBytes   the threshold in bytes, must be non-negative
     */
    public StoragePressureChangedEvent {
        Objects.requireNonNull(oldLevel, "oldLevel cannot be null");
        if (oldLevel.isBlank()) {
            throw new IllegalArgumentException("oldLevel cannot be blank");
        }
        Objects.requireNonNull(newLevel, "newLevel cannot be null");
        if (newLevel.isBlank()) {
            throw new IllegalArgumentException("newLevel cannot be blank");
        }
        if (diskUsageBytes < 0) {
            throw new IllegalArgumentException("diskUsageBytes cannot be negative");
        }
        if (thresholdBytes < 0) {
            throw new IllegalArgumentException("thresholdBytes cannot be negative");
        }
    }
}
