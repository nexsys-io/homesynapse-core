/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.platform.identity;

import java.util.Objects;

/**
 * Typed identifier for a spatial area (room, zone, or floor) within a home.
 *
 * <p>Areas organize entities and devices into logical spatial groups. An area may represent
 * a physical room ("Kitchen"), a zone ("Upstairs"), or any user-defined spatial grouping.
 * Areas are used for scoped automation (e.g., "turn off all lights in the Living Room")
 * and for UI organization.</p>
 *
 * <p>The wrapped value is a {@link Ulid} per LTD-04. Stored as {@code BLOB(16)} in SQLite.</p>
 *
 * @param value the ULID identifying this area, never {@code null}
 */
public record AreaId(Ulid value) implements Comparable<AreaId> {

    /**
     * Validates that the ULID value is non-null.
     *
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public AreaId {
        Objects.requireNonNull(value, "AreaId value must not be null");
    }

    /**
     * Creates an {@code AreaId} from the given ULID.
     *
     * @param value the ULID, never {@code null}
     * @return a new {@code AreaId} instance
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public static AreaId of(Ulid value) {
        return new AreaId(value);
    }

    /**
     * Creates an {@code AreaId} by parsing a 26-character Crockford Base32 ULID string.
     *
     * @param crockford the Crockford Base32 encoded ULID, never {@code null}
     * @return a new {@code AreaId} instance
     * @throws NullPointerException     if {@code crockford} is {@code null}
     * @throws IllegalArgumentException if {@code crockford} is not a valid ULID string
     */
    public static AreaId parse(String crockford) {
        return new AreaId(Ulid.parse(crockford));
    }

    @Override
    public int compareTo(AreaId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
