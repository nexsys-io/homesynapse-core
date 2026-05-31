/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import com.homesynapse.platform.identity.FloorId;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Represents a floor — a vertical level grouping of areas within a home.
 *
 * <p>The floor aggregate introduces a level above {@link Area} in the spatial model,
 * so that a multi-story home can express level-scoped selectors (e.g., "all lights on
 * the ground floor"). Areas reference their owning floor via {@code Area.floorId};
 * a floor is otherwise an independent aggregate carrying a display name, a signed
 * level ordinal, an optional icon, and voice synonyms.</p>
 *
 * <p>The {@code level} is signed so basements and sub-levels are expressible
 * ({@code -1} basement, {@code 0} ground, {@code 1} first, and so on). No uniqueness
 * constraint is placed on {@code level} — split-level homes may legitimately have two
 * floors at the same level (AMD-44 Decision 8).</p>
 *
 * <p>Defined in AMD-44 §2.1.2.</p>
 *
 * @param id        the unique identifier for this floor, never {@code null}
 * @param name      the user-facing display name, never {@code null}, non-blank, at most 100 characters
 * @param level     the signed level ordinal ({@code -1} basement, {@code 0} ground, {@code 1} first, …)
 * @param icon      the Material Design Icons name (e.g. {@code "mdi:home-floor-g"}), {@code null} if unset
 * @param aliases   voice synonyms for this floor; defensively copied and unmodifiable, never {@code null}
 * @param createdAt the timestamp when this floor was created, never {@code null}
 * @see FloorId
 * @see FloorRegistry
 * @see Area
 * @since 1.0
 */
public record Floor(
        FloorId id,
        String name,
        int level,
        String icon,
        List<String> aliases,
        Instant createdAt
) {

    /**
     * Validates required fields and the {@code name} length, and defensively copies
     * {@code aliases} into an unmodifiable list.
     *
     * @throws NullPointerException     if {@code id}, {@code name}, {@code createdAt},
     *                                  {@code aliases}, or any alias element is {@code null}
     * @throws IllegalArgumentException if {@code name} is blank or exceeds 100 characters
     */
    public Floor {
        Objects.requireNonNull(id, "Floor id must not be null");
        Objects.requireNonNull(name, "Floor name must not be null");
        Objects.requireNonNull(createdAt, "Floor createdAt must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Floor name must not be blank");
        }
        if (name.length() > 100) {
            throw new IllegalArgumentException(
                    "Floor name must be <= 100 chars, got " + name.length());
        }
        aliases = List.copyOf(aliases);
    }
}
