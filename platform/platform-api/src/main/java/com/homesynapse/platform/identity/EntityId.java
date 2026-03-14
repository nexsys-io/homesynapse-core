/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

package com.homesynapse.platform.identity;

import java.util.Objects;

/**
 * Typed identifier for a logical entity within HomeSynapse.
 *
 * <p>An entity is the logical abstraction over a device's functional capability — a single
 * physical device may expose multiple entities (e.g., a smart power strip exposes one entity
 * per outlet). Entities are the primary subject of state events ({@code state_reported},
 * {@code state_changed}), command events ({@code command_issued}), and automation triggers.</p>
 *
 * <p>Entity identity is stable across hardware replacements. When a physical device is swapped
 * behind an entity (via {@code entity_transferred}), the {@code EntityId} remains unchanged,
 * preserving the full event history.</p>
 *
 * <p>The wrapped value is a 26-character ULID string in canonical Crockford Base32 encoding
 * per LTD-04. Stored as {@code BLOB(16)} in SQLite.</p>
 *
 * @param value the ULID string identifying this entity, never {@code null} or blank
 * @see DeviceId
 */
public record EntityId(String value) {

    /**
     * Validates that the ULID value is non-null and non-blank.
     *
     * @throws NullPointerException     if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} is blank
     */
    public EntityId {
        Objects.requireNonNull(value, "EntityId value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("EntityId value must not be blank");
        }
    }

    /**
     * Creates an {@code EntityId} from the given ULID string.
     *
     * @param value the ULID string, never {@code null} or blank
     * @return a new {@code EntityId} instance
     * @throws NullPointerException     if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} is blank
     */
    public static EntityId of(String value) {
        return new EntityId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
