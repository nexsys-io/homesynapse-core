/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import com.homesynapse.platform.identity.FloorId;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Registry for managing the lifecycle of {@link Floor} aggregates in HomeSynapse.
 *
 * <p>Provides create, read, update, and delete operations for floors, plus a
 * level-scoped lookup. Floors group {@link Area} aggregates onto physical levels of a
 * multi-story home.</p>
 *
 * <p>Implementations must be safe for concurrent access and must use
 * {@link java.util.concurrent.locks.ReentrantLock} for write serialization rather than
 * {@code synchronized} (LTD-11) — {@code synchronized} pins virtual threads to their
 * carrier threads.</p>
 *
 * <p>Defined in AMD-44 §2.1.3. No implementation ships in Stage 1 — this is an
 * interface-only contract, matching {@link DeviceRegistry} and {@link EntityRegistry}.</p>
 *
 * @see Floor
 * @see FloorId
 * @see AreaRegistry
 * @since 1.0
 */
public interface FloorRegistry {

    /**
     * Creates a new floor and assigns it a fresh {@link FloorId}.
     *
     * @param name    the user-facing display name, never {@code null}, non-blank, at most 100 characters
     * @param level   the signed level ordinal ({@code -1} basement, {@code 0} ground, …)
     * @param icon    the Material Design Icons name, {@code null} if unset
     * @param aliases voice synonyms for the floor, never {@code null}
     * @return the created floor, never {@code null}
     */
    Floor create(String name, int level, String icon, List<String> aliases);

    /**
     * Finds a floor by its identifier, returning empty if not found.
     *
     * @param id the floor identifier, never {@code null}
     * @return an {@link Optional} containing the floor if found, or empty
     */
    Optional<Floor> get(FloorId id);

    /**
     * Returns all registered floors, sorted by {@code level} ascending, then {@code name}
     * ascending, then {@code createdAt} ascending (AMD-44 Decision 8). The compound ordering
     * keeps split-level floors that share a {@code level} deterministically ordered.
     *
     * @return an unmodifiable collection of all floors in the documented order, never {@code null}
     */
    Collection<Floor> getAll();

    /**
     * Returns all floors at the given level. More than one floor may share a level
     * (split-level homes — AMD-44 Decision 8).
     *
     * @param level the level ordinal to match
     * @return an unmodifiable collection of floors at the given level, never {@code null}; empty if none
     */
    Collection<Floor> getByLevel(int level);

    /**
     * Updates an existing floor's mutable fields.
     *
     * @param id      the identifier of the floor to update, never {@code null}
     * @param name    the new display name, never {@code null}, non-blank, at most 100 characters
     * @param level   the new signed level ordinal
     * @param icon    the new Material Design Icons name, {@code null} if unset
     * @param aliases the new voice synonyms, never {@code null}
     * @return the updated floor, never {@code null}
     */
    Floor update(FloorId id, String name, int level, String icon, List<String> aliases);

    /**
     * Deletes a floor.
     *
     * <p>Contract: an implementation rejects deletion while areas are still assigned to the
     * floor. The cascade / force-delete behavior (e.g. an HTTP {@code ?force=true} reassigning
     * those areas to "unassigned") is a REST/implementation concern and is not declared here
     * (AMD-44 Decision 11).</p>
     *
     * @param id the identifier of the floor to delete, never {@code null}
     */
    void delete(FloorId id);
}
