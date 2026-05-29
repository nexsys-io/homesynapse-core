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
 * <h2>Projection Version — sentinel, NOT authoritative (REC-82)</h2>
 *
 * <p><b>Do not use {@link #projectionVersion()} for reconciliation decisions.</b>
 * Both {@code ViewCheckpointStore} implementations
 * ({@code InMemoryViewCheckpointStore} and {@code SqliteViewCheckpointStore})
 * hardcode this field to the sentinel value {@code 1}. The authoritative
 * projection version lives inside the opaque {@code data} blob and is recovered
 * via {@link StateCheckpointSource#loadedProjectionVersion()} (AMD-41 §3.2.4).
 * {@code StateProjection.initialize()} reconciles against that loaded value, not
 * this field. The accessor is {@code @Deprecated} so a future caller cannot
 * silently bind to the sentinel.</p>
 *
 * <p>Defined in Doc 03 §8.3.</p>
 *
 * @param viewName the identifier of the materialized view that produced this checkpoint,
 *        never {@code null}
 * @param position the global event log position at which this checkpoint was taken
 * @param data the opaque serialized checkpoint content, never {@code null}
 * @param writtenAt the wall-clock time when this checkpoint was written, never {@code null}
 * @param projectionVersion the version of the projection logic that produced this
 *        checkpoint. <b>Sentinel only</b> — hardcoded to {@code 1} by all store
 *        implementations; the real version is in {@code data}. See
 *        {@link #projectionVersion()}.
 * @see ViewCheckpointStore
 * @see StateCheckpointSource#loadedProjectionVersion()
 * @see StateStoreLifecycle
 * @since 1.0
 */
public record CheckpointRecord(
        String viewName,
        long position,
        byte[] data,
        Instant writtenAt,
        int projectionVersion
) {

    /**
     * Returns the stored projection-version field.
     *
     * @deprecated This is a sentinel hardcoded to {@code 1} by every
     *             {@link ViewCheckpointStore} implementation — it is NOT the
     *             authoritative projection version and MUST NOT be used for the
     *             reconciliation version-mismatch check (REC-82, AMD-41 §3.2.4).
     *             The real version lives in the checkpoint {@code data} blob; use
     *             {@link StateCheckpointSource#loadedProjectionVersion()} instead.
     *             This accessor remains only for record-mechanics callers (the
     *             contract test that asserts the sentinel invariant and the store
     *             implementations that round-trip the record).
     * @return the stored sentinel projection version (always {@code 1} in
     *         practice)
     */
    @Deprecated
    public int projectionVersion() {
        return projectionVersion;
    }
}
