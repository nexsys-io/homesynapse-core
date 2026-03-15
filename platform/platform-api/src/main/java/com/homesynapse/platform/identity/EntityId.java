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
 * <p>The wrapped value is a {@link Ulid} per LTD-04. Stored as {@code BLOB(16)} in SQLite.</p>
 *
 * @param value the ULID identifying this entity, never {@code null}
 * @see DeviceId
 */
public record EntityId(Ulid value) implements Comparable<EntityId> {

    /**
     * Validates that the ULID value is non-null.
     *
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public EntityId {
        Objects.requireNonNull(value, "EntityId value must not be null");
    }

    /**
     * Creates an {@code EntityId} from the given ULID.
     *
     * @param value the ULID, never {@code null}
     * @return a new {@code EntityId} instance
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public static EntityId of(Ulid value) {
        return new EntityId(value);
    }

    /**
     * Creates an {@code EntityId} by parsing a 26-character Crockford Base32 ULID string.
     *
     * @param crockford the Crockford Base32 encoded ULID, never {@code null}
     * @return a new {@code EntityId} instance
     * @throws NullPointerException     if {@code crockford} is {@code null}
     * @throws IllegalArgumentException if {@code crockford} is not a valid ULID string
     */
    public static EntityId parse(String crockford) {
        return new EntityId(Ulid.parse(crockford));
    }

    @Override
    public int compareTo(EntityId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
