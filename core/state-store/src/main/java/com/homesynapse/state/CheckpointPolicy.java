/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

import java.time.Duration;

/**
 * Determines when a subscriber should flush its checkpoint to durable storage.
 *
 * <p>The checkpoint policy serves two purposes:
 * <ol>
 *   <li><b>Crash recovery:</b> bounds the number of events replayed after restart.
 *       A subscriber crashing between checkpoints reprocesses up to
 *       {@code eventThreshold} events on recovery.</li>
 *   <li><b>WAL release:</b> closing the subscriber's read transaction allows
 *       SQLite's WAL checkpoint to advance past the reader's position, preventing
 *       unbounded WAL growth under sustained writer load (AMD-38).</li>
 * </ol>
 *
 * <p>Implementations receive the subscriber's current lag behind the writer as
 * {@code readerLag}. Fixed policies ignore this parameter; adaptive policies use
 * it to shift between normal and pressure modes.
 *
 * <p>Subscribers using any checkpoint policy <b>MUST be idempotent</b> for up to
 * {@code eventThreshold} events, because crash recovery replays from the last
 * checkpoint. Re-processing the same events must produce identical state — this
 * is INV-ES-05 (at-least-once delivery with subscriber idempotency).
 *
 * <p>The sealed permits are restricted to {@link FixedCheckpointPolicy} and
 * {@link AdaptiveCheckpointPolicy}. Adding a new permit requires a Phase 2
 * amendment because the projection-loop dispatch logic depends on the closed
 * set of policy types.
 *
 * @see FixedCheckpointPolicy
 * @see AdaptiveCheckpointPolicy
 */
public sealed interface CheckpointPolicy
        permits FixedCheckpointPolicy, AdaptiveCheckpointPolicy {

    /**
     * Returns whether the subscriber should checkpoint now.
     *
     * <p>Called by the projection loop after each batch of events is applied.
     * A {@code true} return value triggers a checkpoint flush, which closes the
     * subscriber's read transaction and persists the current position via
     * {@code ViewCheckpointStore}.
     *
     * @param eventsSinceLastCheckpoint events processed since the last
     *                                  checkpoint flush (≥ 0)
     * @param timeSinceLastCheckpoint   wall-clock duration since the last
     *                                  checkpoint flush (non-null, non-negative)
     * @param readerLag                 current gap between the writer's head
     *                                  position and this subscriber's
     *                                  last-processed position
     *                                  ({@code 0} = caught up). Fixed policies
     *                                  may ignore this parameter; adaptive
     *                                  policies use it to switch between modes.
     * @return {@code true} if the subscriber should flush its checkpoint
     */
    boolean shouldCheckpoint(long eventsSinceLastCheckpoint,
                             Duration timeSinceLastCheckpoint,
                             long readerLag);
}
