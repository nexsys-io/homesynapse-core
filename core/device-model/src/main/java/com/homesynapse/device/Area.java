/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import com.homesynapse.platform.identity.AreaId;
import com.homesynapse.platform.identity.FloorId;

import java.time.Instant;
import java.util.Objects;

/**
 * Represents an area — a user-defined spatial grouping (room, zone, …) within a home.
 *
 * <p>This is the minimal area aggregate introduced in AMD-44 Stage 1 so that an area's
 * floor membership ({@code floorId}) is structurally expressible. Full area lifecycle
 * (create/update/delete, lifecycle events, migration) is deferred to AMD-45.</p>
 *
 * <p>The {@code floorId} is nullable: a {@code null} value means the area is not assigned
 * to any floor. There is no synthetic "Unassigned" floor (AMD-44 Decision 5).</p>
 *
 * <p>Defined in AMD-44 §2.2.</p>
 *
 * @param id        the unique identifier for this area, never {@code null}
 * @param name      the user-facing display name, never {@code null}, non-blank, at most 100 characters
 * @param floorId   the owning floor's identifier, {@code null} if the area is unassigned to any floor
 * @param createdAt the timestamp when this area was created, never {@code null}
 * @see AreaId
 * @see FloorId
 * @see AreaRegistry
 * @since 1.0
 */
public record Area(
        AreaId id,
        String name,
        FloorId floorId,
        Instant createdAt
) {

    /**
     * Validates required fields and the {@code name} length.
     *
     * @throws NullPointerException     if {@code id}, {@code name}, or {@code createdAt} is {@code null}
     * @throws IllegalArgumentException if {@code name} is blank or exceeds 100 characters
     */
    public Area {
        Objects.requireNonNull(id, "Area id must not be null");
        Objects.requireNonNull(name, "Area name must not be null");
        Objects.requireNonNull(createdAt, "Area createdAt must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Area name must not be blank");
        }
        if (name.length() > 100) {
            throw new IllegalArgumentException(
                    "Area name must be <= 100 chars, got " + name.length());
        }
    }
}
