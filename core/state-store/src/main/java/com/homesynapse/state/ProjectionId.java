/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

import java.util.Objects;

/**
 * Typed wrapper for projection view identifiers (AMD-41 §3.2.3).
 *
 * <p>Each materialized projection (e.g., the entity-state projection, future energy
 * analytics projection) is identified by a stable, human-readable string. The string
 * value is used as the view name passed to {@link ViewCheckpointStore#writeCheckpoint}
 * and {@link ViewCheckpointStore#readLatestCheckpoint}.</p>
 *
 * <p>Unlike domain identity wrappers ({@code EntityId}, {@code DeviceId}, etc.) which
 * use ULIDs, {@code ProjectionId} uses a plain {@link String} because projection names
 * are stable infrastructure identifiers chosen at compile time, not domain objects
 * with their own identity lifecycle.</p>
 *
 * @param value the projection identifier, never {@code null} or blank
 */
public record ProjectionId(String value) {

    /**
     * Validates the projection identifier.
     *
     * @throws NullPointerException     if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} is blank
     */
    public ProjectionId {
        Objects.requireNonNull(value, "value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("ProjectionId value must not be blank");
        }
    }
}
