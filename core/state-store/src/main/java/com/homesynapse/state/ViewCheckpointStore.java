/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

import java.util.Optional;

/**
 * Durable storage for materialized view checkpoints, enabling crash-safe recovery
 * of projected state without full event replay.
 *
 * <p>{@code ViewCheckpointStore} is consumed by the State Store and implemented by the
 * Persistence Layer (Doc 04). It stores opaque serialized checkpoint data keyed by
 * view name, allowing multiple materialized views to share the same checkpoint
 * infrastructure. The State Store uses {@code "entity_state"} as its view name;
 * future projections (e.g., energy analytics) use different view names.</p>
 *
 * <h2>Distinction from Event Bus CheckpointStore</h2>
 *
 * <p>This interface is <strong>not</strong> the same as
 * {@link com.homesynapse.event.bus.CheckpointStore}, which stores subscriber
 * position checkpoints for the pull-based event bus subscription model (Doc 01 §3.4).
 * {@code ViewCheckpointStore} stores serialized <em>view state</em> — a richer
 * payload containing the full materialized state needed for crash recovery.
 * The event bus {@code CheckpointStore} stores only a single {@code long} position
 * per subscriber.</p>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>Implementations must be safe for concurrent use. The State Store may read
 * a checkpoint during startup while a background process writes checkpoints for
 * other views.</p>
 *
 * <p>Defined in Doc 03 §8.3.</p>
 *
 * @see CheckpointRecord
 * @see StateStoreLifecycle
 * @see com.homesynapse.event.bus.CheckpointStore
 * @since 1.0
 */
public interface ViewCheckpointStore {

    /**
     * Writes a checkpoint for the specified materialized view.
     *
     * <p>The checkpoint data is opaque — the persistence layer stores and retrieves
     * it without interpreting the content. The data must be durable before this
     * method returns.</p>
     *
     * @param viewName the identifier of the materialized view (e.g., {@code "entity_state"}),
     *        never {@code null}
     * @param position the global event log position at which this checkpoint was taken
     * @param data the opaque serialized checkpoint content, never {@code null}
     * @throws NullPointerException if {@code viewName} or {@code data} is {@code null}
     */
    void writeCheckpoint(String viewName, long position, byte[] data);

    /**
     * Reads the most recent checkpoint for the specified materialized view.
     *
     * <p>Returns the latest {@link CheckpointRecord} stored for the given view name,
     * or empty if no checkpoint has been written for this view. The caller is
     * responsible for validating the checkpoint's {@link CheckpointRecord#projectionVersion()}
     * against the running code's version to decide whether to use or discard it.</p>
     *
     * @param viewName the identifier of the materialized view (e.g., {@code "entity_state"}),
     *        never {@code null}
     * @return an {@link Optional} containing the latest checkpoint if one exists,
     *         or empty
     * @throws NullPointerException if {@code viewName} is {@code null}
     */
    Optional<CheckpointRecord> readLatestCheckpoint(String viewName);
}
