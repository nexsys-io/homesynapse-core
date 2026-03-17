/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

import com.homesynapse.platform.identity.EntityId;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Read-only query interface for the materialized entity state view.
 *
 * <p>{@code StateQueryService} is the single most frequently called interface in
 * HomeSynapse. Every downstream consumer — REST API, WebSocket API, Automation
 * Engine, Web UI — queries this interface for current entity state instead of
 * scanning event streams.</p>
 *
 * <h2>Consistency Model</h2>
 *
 * <p>The consistency guarantees vary by method:</p>
 * <ul>
 *   <li><strong>Per-entity reads</strong> ({@link #getState(EntityId)}) — consistent.
 *       The returned {@link EntityState} reflects all events processed up to the
 *       moment of the read.</li>
 *   <li><strong>Cross-entity batch reads</strong> ({@link #getStates(Set)}) — weakly
 *       consistent. Individual entity states are consistent, but the batch as a whole
 *       may span multiple projection ticks. Entity A's state may reflect a later
 *       event than entity B's state in the same batch.</li>
 *   <li><strong>Snapshot reads</strong> ({@link #getSnapshot()}) — fully consistent.
 *       All entity states in the snapshot correspond to the same
 *       {@link StateSnapshot#viewPosition()} in the event log.</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>All methods are lock-free reads from a {@code ConcurrentHashMap} and are safe
 * for concurrent use from any thread, including virtual threads.</p>
 *
 * <h2>Filtered Queries</h2>
 *
 * <p>This interface does not support filtered queries (e.g., "get all entities in
 * area X" or "get all entities with capability Y"). Filtered queries combine state
 * data with {@link com.homesynapse.device.EntityRegistry} structural metadata at
 * the caller — this is the API Layer's responsibility. This is an explicit design
 * decision (Doc 03 §8.1).</p>
 *
 * <p>Defined in Doc 03 §8.1.</p>
 *
 * @see EntityState
 * @see StateSnapshot
 * @see StateStoreLifecycle
 * @see com.homesynapse.event.EventEnvelope
 * @since 1.0
 */
public interface StateQueryService {

    /**
     * Retrieves the current materialized state of a single entity.
     *
     * <p>Returns the entity's state if it exists in the state view, or empty if the
     * entity has never been projected (no events received for this entity). This is
     * a lock-free read.</p>
     *
     * @param entityId the entity identifier to query, never {@code null}
     * @return an {@link Optional} containing the entity state if present, or empty
     */
    Optional<EntityState> getState(EntityId entityId);

    /**
     * Retrieves the current materialized state of multiple entities in a single call.
     *
     * <p>Returns a map containing only the entities that exist in the state view.
     * Entities that have never been projected are omitted from the result (the map
     * will not contain their keys). The returned map is unmodifiable.</p>
     *
     * <p><strong>Consistency:</strong> Individual entity states are consistent, but
     * the batch as a whole is weakly consistent — entity states may reflect different
     * points in the event stream.</p>
     *
     * @param entityIds the set of entity identifiers to query, never {@code null}
     * @return an unmodifiable map of entity states keyed by entity identifier;
     *         never {@code null}, may be empty
     */
    Map<EntityId, EntityState> getStates(Set<EntityId> entityIds);

    /**
     * Creates a fully consistent point-in-time snapshot of the entire state view.
     *
     * <p>The snapshot captures all entity states at the same event log position,
     * providing full cross-entity consistency. This is an O(N) operation where N
     * is the number of tracked entities.</p>
     *
     * <p>Intended for infrequent use: initial page loads, bulk API requests,
     * WebSocket initial synchronization, and checkpoint serialization. Do not use
     * for hot-path polling — use {@link #getState(EntityId)} or
     * {@link #getStates(Set)} instead.</p>
     *
     * @return an immutable snapshot of the entire state view, never {@code null}
     */
    StateSnapshot getSnapshot();

    /**
     * Returns the current global event log position of the state view.
     *
     * <p>The view position is monotonically increasing and corresponds to the
     * {@code global_position} of the last event processed by the state projection.
     * Consumers can use this to detect whether the view has advanced since their
     * last read.</p>
     *
     * @return the current view position, or 0 if no events have been processed
     */
    long getViewPosition();

    /**
     * Indicates whether the state store has completed startup replay and is ready
     * to serve current data.
     *
     * <p>Returns {@code false} during startup replay (while the state projection
     * is catching up from a checkpoint). Returns {@code true} after the
     * {@link StateStoreLifecycle#start()} future completes.</p>
     *
     * <p>The REST API should return 503 Service Unavailable when this method returns
     * {@code false}, unless the caller explicitly accepts stale data (Doc 03 §3.6).</p>
     *
     * @return {@code true} if the state view is current, {@code false} if replay
     *         is in progress
     */
    boolean isReady();
}
