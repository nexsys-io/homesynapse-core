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
 * <p>The wrapped value is a 26-character ULID string in canonical Crockford Base32 encoding
 * per LTD-04. Stored as {@code BLOB(16)} in SQLite.</p>
 *
 * @param value the ULID string identifying this area, never {@code null} or blank
 */
public record AreaId(String value) {

    /**
     * Validates that the ULID value is non-null and non-blank.
     *
     * @throws NullPointerException     if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} is blank
     */
    public AreaId {
        Objects.requireNonNull(value, "AreaId value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("AreaId value must not be blank");
        }
    }

    /**
     * Creates an {@code AreaId} from the given ULID string.
     *
     * @param value the ULID string, never {@code null} or blank
     * @return a new {@code AreaId} instance
     * @throws NullPointerException     if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} is blank
     */
    public static AreaId of(String value) {
        return new AreaId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
