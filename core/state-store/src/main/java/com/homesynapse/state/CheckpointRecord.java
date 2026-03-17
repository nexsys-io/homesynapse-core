/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

import java.time.Instant;

/**
 * Immutable record representing a stored checkpoint for a materialized view.
 *
 * <p>{@code CheckpointRecord} is the return type of
 * {@link ViewCheckpointStore#readLatestCheckpoint(String)}. It captures a snapshot
 * of a view's serialized state at a specific position in the event log, enabling
 * crash-safe recovery without full event replay.</p>
 *
 * <h2>Opaque Data</h2>
 *
 * <p>The {@code data} field contains opaque serialized checkpoint content. The
 * {@link ViewCheckpointStore} (implemented by the Persistence Layer, Doc 04) stores
 * and retrieves this data without interpreting its contents. In Phase 3, the
 * serialization format will be JSON via Jackson (LTD-08).</p>
 *
 * <h2>View Names</h2>
 *
 * <p>The {@code viewName} field supports multiple materialized views sharing the
 * same checkpoint infrastructure. The State Store uses {@code "entity_state"} as its
 * view name. Future projections (e.g., energy analytics) use different view names.</p>
 *
 * <h2>Projection Version</h2>
 *
 * <p>The {@code projectionVersion} field enables version-aware checkpoint invalidation.
 * When the projection logic changes in a way that alters the materialized state
 * structure, the projection version is incremented. On startup, if the stored
 * checkpoint's projection version does not match the running code's version, the
 * checkpoint is discarded and a full replay is performed.</p>
 *
 * <p>Defined in Doc 03 §8.3.</p>
 *
 * @param viewName the identifier of the materialized view that produced this checkpoint,
 *        never {@code null}
 * @param position the global event log position at which this checkpoint was taken
 * @param data the opaque serialized checkpoint content, never {@code null}
 * @param writtenAt the wall-clock time when this checkpoint was written, never {@code null}
 * @param projectionVersion the version of the projection logic that produced this
 *        checkpoint, used for version-aware invalidation
 * @see ViewCheckpointStore
 * @see StateStoreLifecycle
 * @since 1.0
 */
public record CheckpointRecord(
        String viewName,
        long position,
        byte[] data,
        Instant writtenAt,
        int projectionVersion
) { }
