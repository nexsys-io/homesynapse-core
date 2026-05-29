/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

import java.util.Objects;

/**
 * Injection seam for the atomic subscriber+view checkpoint coupling
 * (AMD-45 §2.1, AMD-45-INV-01).
 *
 * <p>{@code AtomicCheckpointSink} decouples {@link StateProjection} from the
 * persistence module's package-private {@code AtomicCheckpointWriter}: the
 * projection holds an interface reference, and the composition root supplies an
 * implementation backed by the SQLite atomic write path. The seam follows the
 * same direction as {@link StateCheckpointSource} and {@link ViewCheckpointStore}
 * — a consumer-defined interface in state-store that persistence implements —
 * which is the only direction permitted by the inward-only module dependency
 * graph ({@code com.homesynapse.persistence requires com.homesynapse.state},
 * never the reverse). See AMD-45 §5: "interface in state-store, implementation
 * in persistence."</p>
 *
 * <h2>Why atomic coupling exists (AMD-45 §1)</h2>
 *
 * <p>Before AMD-45, two checkpoint domains advanced independently: the bus
 * wrote the {@code subscriber_checkpoints} position after every LIVE delivery,
 * while the projection wrote the {@code view_checkpoints} snapshot only on
 * policy cadence. A crash between the two writes left the bus believing events
 * were delivered that the view snapshot never captured — silent state loss on
 * recovery. {@code AtomicCheckpointSink} closes that window: the subscriber
 * position and the view snapshot are written in a single SQLite transaction,
 * so neither advances without the other. The bus's per-delivery subscriber
 * checkpoint write is correspondingly suppressed for the projection subscriber
 * (AMD-45 §2.2, {@code SubscriberInfo.atomicCheckpoint}).</p>
 *
 * <h2>Identity</h2>
 *
 * <p>For the materialized state projection the bus subscriber id and the view
 * name are the same stable identifier ({@code "state_projection"}); the
 * projection passes its {@link ProjectionId} value as the single
 * {@code checkpointKey} and the persistence implementation uses it for both the
 * {@code subscriber_checkpoints} and {@code view_checkpoints} rows.</p>
 *
 * @see StateProjection
 * @see StateCheckpointSource
 * @see ViewCheckpointStore
 */
@FunctionalInterface
public interface AtomicCheckpointSink {

    /**
     * Atomically persists the coupled subscriber checkpoint and view checkpoint
     * for a projection at the given position (AMD-45-INV-01).
     *
     * <p>The production implementation writes both the subscriber position and
     * the view snapshot in one SQLite transaction; if either write fails,
     * neither is committed. The {@code checkpointKey} is used as both the
     * subscriber id ({@code subscriber_checkpoints}) and the view name
     * ({@code view_checkpoints}).</p>
     *
     * @param checkpointKey the projection's stable identifier, used as both the
     *                      subscriber id and the view name; never {@code null}
     * @param position      the {@code global_position} being checkpointed;
     *                      non-negative
     * @param viewData      the opaque serialized view snapshot; never
     *                      {@code null}
     */
    void writeAtomicCheckpoint(String checkpointKey, long position, byte[] viewData);

    /**
     * Returns a sink that writes ONLY the view checkpoint, with no coupled
     * subscriber checkpoint.
     *
     * <p>Suitable for tests and in-memory deployments where there is no bus
     * {@code subscriber_checkpoints} table to keep in sync — the coupling
     * invariant is trivially satisfied because only the view checkpoint exists.
     * The {@code checkpointKey} is passed through to
     * {@link ViewCheckpointStore#writeCheckpoint(String, long, byte[])} as the
     * view name, preserving the pre-AMD-45 write behavior.</p>
     *
     * @param store the view checkpoint store to write through; never {@code null}
     * @return a view-only sink delegating to {@code store}
     * @throws NullPointerException if {@code store} is {@code null}
     */
    static AtomicCheckpointSink viewOnly(ViewCheckpointStore store) {
        Objects.requireNonNull(store, "store must not be null");
        return (checkpointKey, position, viewData) ->
                store.writeCheckpoint(checkpointKey, position, viewData);
    }
}
