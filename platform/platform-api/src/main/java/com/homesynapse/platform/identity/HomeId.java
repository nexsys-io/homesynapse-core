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
 * <p>The wrapped value is a {@link Ulid} per LTD-04. Stored as {@code BLOB(16)} in SQLite.</p>
 *
 * @param value the ULID identifying this home, never {@code null}
 * @see SystemId
 */
public record HomeId(Ulid value) implements Comparable<HomeId> {

    /**
     * Validates that the ULID value is non-null.
     *
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public HomeId {
        Objects.requireNonNull(value, "HomeId value must not be null");
    }

    /**
     * Creates a {@code HomeId} from the given ULID.
     *
     * @param value the ULID, never {@code null}
     * @return a new {@code HomeId} instance
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public static HomeId of(Ulid value) {
        return new HomeId(value);
    }

    /**
     * Creates a {@code HomeId} by parsing a 26-character Crockford Base32 ULID string.
     *
     * @param crockford the Crockford Base32 encoded ULID, never {@code null}
     * @return a new {@code HomeId} instance
     * @throws NullPointerException     if {@code crockford} is {@code null}
     * @throws IllegalArgumentException if {@code crockford} is not a valid ULID string
     */
    public static HomeId parse(String crockford) {
        return new HomeId(Ulid.parse(crockford));
    }

    @Override
    public int compareTo(HomeId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
