/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import com.homesynapse.platform.identity.AreaId;
import com.homesynapse.platform.identity.FloorId;

import java.util.Collection;
import java.util.Optional;

/**
 * Read-only registry for looking up {@link Area} aggregates in HomeSynapse.
 *
 * <p>Stage 1 exposes lookup only — there is no create, update, or delete here. Area
 * write lifecycle (CRUD, lifecycle events, migration) is deferred to AMD-45. This
 * interface exists in Stage 1 so that floor-scoped area queries are expressible
 * (AMD-44 §2.2 / Decision 14).</p>
 *
 * <p>Implementations must be safe for concurrent read access and must use
 * {@link java.util.concurrent.locks.ReentrantLock} rather than {@code synchronized} for
 * any internal synchronization (LTD-11). No implementation ships in Stage 1 — this is an
 * interface-only contract, matching {@link DeviceRegistry} and {@link EntityRegistry}.</p>
 *
 * @see Area
 * @see FloorRegistry
 * @since 1.0
 */
public interface AreaRegistry {

    /**
     * Finds an area by its identifier, returning empty if not found.
     *
     * @param id the area identifier, never {@code null}
     * @return an {@link Optional} containing the area if found, or empty
     */
    Optional<Area> get(AreaId id);

    /**
     * Returns all registered areas.
     *
     * @return an unmodifiable collection of all areas, never {@code null}
     */
    Collection<Area> getAll();

    /**
     * Returns all areas assigned to the given floor.
     *
     * @param floorId the floor identifier, never {@code null}
     * @return an unmodifiable collection of areas on the floor, never {@code null}; empty if none
     */
    Collection<Area> getByFloor(FloorId floorId);

    /**
     * Returns all areas not assigned to any floor (those with {@code floorId == null}).
     *
     * @return an unmodifiable collection of unassigned areas, never {@code null}; empty if none
     */
    Collection<Area> getUnassigned();
}
