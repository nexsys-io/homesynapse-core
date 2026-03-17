/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

import com.homesynapse.platform.identity.EntityId;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Point-in-time immutable copy of the entire materialized state view.
 *
 * <p>{@code StateSnapshot} captures the full state of all entities at a consistent
 * point in the event stream. Unlike individual {@link EntityState} reads (which are
 * per-entity consistent but weakly consistent across entities), a snapshot provides
 * full cross-entity consistency — all entity states correspond to the same
 * {@code viewPosition} in the event log.</p>
 *
 * <h2>Performance Characteristics</h2>
 *
 * <p>Creating a snapshot is an O(N) operation where N is the number of entities in
 * the state view. This is intended for infrequent use cases:</p>
 * <ul>
 *   <li>Bulk API endpoints (initial page loads, full-state REST responses)</li>
 *   <li>WebSocket initial synchronization (send full state on client connect)</li>
 *   <li>Checkpoint serialization (periodic state persistence for crash recovery)</li>
 * </ul>
 *
 * <p>Do not use snapshots for hot-path polling. Use
 * {@link StateQueryService#getState(EntityId)} for individual entity lookups
 * and {@link StateQueryService#getStates(Set)} for targeted batch reads.</p>
 *
 * <h2>Disabled Entities</h2>
 *
 * <p>The {@code disabledEntities} set enables query consumers to distinguish
 * disabled-with-frozen-state from enabled-with-current-state without consulting
 * the entity registry. Disabled entities retain their last known state in the
 * {@code states} map but do not receive projection updates.</p>
 *
 * <p>Defined in Doc 03 §4.2.</p>
 *
 * @param states the current state of all tracked entities, keyed by entity identifier;
 *        unmodifiable, never {@code null}
 * @param viewPosition the global event log position this snapshot corresponds to
 * @param snapshotTime the wall-clock time when this snapshot was created, never {@code null}
 * @param replaying {@code true} if the state store is currently replaying events from a
 *        checkpoint during startup; {@code false} after the view is current. Consumers
 *        use this to detect catching-up state — the REST API returns 503 during replay
 *        unless the caller accepts stale data (Doc 03 §3.6).
 * @param disabledEntities the set of entity identifiers that are administratively
 *        disabled; unmodifiable, never {@code null}
 * @see StateQueryService#getSnapshot()
 * @see EntityState
 * @see StateStoreLifecycle
 * @since 1.0
 */
public record StateSnapshot(
        Map<EntityId, EntityState> states,
        long viewPosition,
        Instant snapshotTime,
        boolean replaying,
        Set<EntityId> disabledEntities
) { }
