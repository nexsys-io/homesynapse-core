/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.platform.identity;

import java.util.Objects;

/**
 * Typed identifier for a floor — a vertical level grouping of areas within a home.
 *
 * <p>A floor aggregates one or more areas onto a single physical level of a multi-story
 * home (e.g., "Ground Floor", "First Floor", "Basement"). Floors are used to express
 * level-scoped selectors such as "all lights on the ground floor" that a flat area model
 * cannot capture. Areas reference their owning floor via {@code Area.floorId}.</p>
 *
 * <p>The wrapped value is a {@link Ulid} per LTD-04. Stored as {@code BLOB(16)} in SQLite;
 * the Crockford Base32 string form is used only at API and log boundaries.</p>
 *
 * @param value the ULID identifying this floor, never {@code null}
 */
public record FloorId(Ulid value) implements Comparable<FloorId> {

    /**
     * Validates that the ULID value is non-null.
     *
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public FloorId {
        Objects.requireNonNull(value, "FloorId value must not be null");
    }

    /**
     * Creates a {@code FloorId} from the given ULID.
     *
     * @param value the ULID, never {@code null}
     * @return a new {@code FloorId} instance
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public static FloorId of(Ulid value) {
        return new FloorId(value);
    }

    /**
     * Creates a {@code FloorId} by parsing a 26-character Crockford Base32 ULID string.
     *
     * @param crockford the Crockford Base32 encoded ULID, never {@code null}
     * @return a new {@code FloorId} instance
     * @throws NullPointerException     if {@code crockford} is {@code null}
     * @throws IllegalArgumentException if {@code crockford} is not a valid ULID string
     */
    public static FloorId parse(String crockford) {
        return new FloorId(Ulid.parse(crockford));
    }

    @Override
    public int compareTo(FloorId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
