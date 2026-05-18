/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

import com.homesynapse.platform.identity.EntityId;

import java.util.Map;
import java.util.Optional;

/**
 * Port for materialized entity-state storage.
 *
 * <p>{@code StateStore} is the write-side port consumed by {@link StateProjection}.
 * It is implemented by an in-memory fixture in the {@code testFixtures} source set
 * for M3.5a and will be implemented by a SQLite-backed adapter in M3.5b. The
 * downstream {@code StateQueryService} (M3.6) wraps the same backing store and
 * exposes read-only queries.</p>
 *
 * <p>Implementations MUST be safe for concurrent reads from any thread, including
 * virtual threads (LTD-11). Writes occur exclusively from the projection's
 * subscriber virtual thread.</p>
 *
 * <p>The {@code clear()} operation supports the reconciliation pass (AMD-41 §3.2.4):
 * when a stored checkpoint's {@code projectionVersion} does not match the running
 * code's version, the projection discards the checkpoint and clears the store so
 * the bus's REPLAY mechanism can rebuild state from position 0.</p>
 *
 * @see StateProjection
 * @see EntityState
 */
public interface StateStore {

    /**
     * Returns the current {@link EntityState} for the given entity, if present.
     *
     * @param entityId the entity identifier; never {@code null}
     * @return the entity's current state, or empty if the entity has not received
     *         any events yet
     * @throws NullPointerException if {@code entityId} is {@code null}
     */
    Optional<EntityState> get(EntityId entityId);

    /**
     * Stores the materialized state for the given entity, replacing any previous
     * state.
     *
     * @param entityId the entity identifier; never {@code null}
     * @param state    the entity's new materialized state; never {@code null}
     * @throws NullPointerException if either parameter is {@code null}
     */
    void put(EntityId entityId, EntityState state);

    /**
     * Returns an unmodifiable view of all currently-stored entity states.
     *
     * <p>Returned map is a snapshot; implementations MAY return a defensive copy
     * or an unmodifiable wrapper over the backing store. Callers MUST treat the
     * returned map as read-only.</p>
     *
     * @return all entity states keyed by entity identifier, never {@code null}
     */
    Map<EntityId, EntityState> getAll();

    /**
     * Atomically removes all entity states from the store.
     *
     * <p>Used by the reconciliation pass (AMD-41 §3.2.4) when the persisted
     * checkpoint's {@code projectionVersion} does not match the running code's
     * version: the projection clears the store and resets its cursor so the bus
     * REPLAYs from position 0.</p>
     */
    void clear();
}
