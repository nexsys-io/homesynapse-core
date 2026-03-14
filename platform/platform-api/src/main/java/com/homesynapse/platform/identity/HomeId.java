/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

package com.homesynapse.platform.identity;

import java.util.Objects;

/**
 * Typed identifier for a home (site, dwelling) managed by HomeSynapse.
 *
 * <p>The home ID identifies the physical dwelling or site that HomeSynapse manages. In the
 * MVP single-home deployment model, there is exactly one {@code HomeId} per installation.
 * The home ID is the top-level grouping identity — all devices, entities, areas, and
 * automations belong to a home.</p>
 *
 * <p>The home ID is distinct from the {@linkplain SystemId system ID}: the system ID
 * identifies the software instance, while the home ID identifies the physical site. A
 * system reinstallation on new hardware produces a new system ID but should retain the
 * same home ID if managing the same dwelling.</p>
 *
 * <p>The wrapped value is a 26-character ULID string in canonical Crockford Base32 encoding
 * per LTD-04. Stored as {@code BLOB(16)} in SQLite.</p>
 *
 * @param value the ULID string identifying this home, never {@code null} or blank
 * @see SystemId
 */
public record HomeId(String value) {

    /**
     * Validates that the ULID value is non-null and non-blank.
     *
     * @throws NullPointerException     if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} is blank
     */
    public HomeId {
        Objects.requireNonNull(value, "HomeId value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("HomeId value must not be blank");
        }
    }

    /**
     * Creates a {@code HomeId} from the given ULID string.
     *
     * @param value the ULID string, never {@code null} or blank
     * @return a new {@code HomeId} instance
     * @throws NullPointerException     if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} is blank
     */
    public static HomeId of(String value) {
        return new HomeId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
