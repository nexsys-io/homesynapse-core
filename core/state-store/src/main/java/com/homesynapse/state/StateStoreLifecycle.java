/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

import java.util.concurrent.CompletableFuture;

/**
 * Lifecycle management interface for the State Store subsystem.
 *
 * <p>{@code StateStoreLifecycle} controls the startup and shutdown of the state
 * projection, including checkpoint loading, event replay, and final checkpoint
 * persistence. It is consumed by the Startup &amp; Lifecycle subsystem (Doc 12)
 * which coordinates ordered initialization of all HomeSynapse subsystems.</p>
 *
 * <h2>Startup Sequence</h2>
 *
 * <p>When {@link #start()} is called, the state store performs the following steps:</p>
 * <ol>
 *   <li>Loads the latest checkpoint from {@link ViewCheckpointStore} (if one exists)</li>
 *   <li>Validates the checkpoint's projection version against the running code</li>
 *   <li>Begins replaying events from the checkpoint position (or from the start of
 *       the event log if no valid checkpoint exists)</li>
 *   <li>The returned {@link CompletableFuture} completes when replay finishes and
 *       the view is current</li>
 * </ol>
 *
 * <p>During replay, {@link StateQueryService#isReady()} returns {@code false} and
 * {@link StateSnapshot#replaying()} returns {@code true}. Dependent subsystems
 * should not start until the returned future completes.</p>
 *
 * <h2>Shutdown Sequence</h2>
 *
 * <p>When {@link #stop()} is called, the state store writes a final checkpoint
 * (to minimize replay time on next startup) and stops the projection subscriber.
 * After {@code stop()} returns, the state view is frozen — no further events are
 * processed.</p>
 *
 * <p>Defined in Doc 03 §8.2.</p>
 *
 * @see StateQueryService
 * @see ViewCheckpointStore
 * @see com.homesynapse.event.EventEnvelope
 * @since 1.0
 */
public interface StateStoreLifecycle {

    /**
     * Starts the state store: loads checkpoint, begins event replay, and returns
     * a future that completes when the view is current.
     *
     * <p>The returned future completes normally when the state projection has caught
     * up to the current end of the event log and is ready to serve live queries.
     * The future completes exceptionally if checkpoint loading or replay fails
     * irrecoverably.</p>
     *
     * <p>This method is idempotent — calling it when the store is already started
     * has no effect and returns an already-completed future.</p>
     *
     * @return a future that completes when the state view is current, never {@code null}
     */
    CompletableFuture<Void> start();

    /**
     * Stops the state store: writes a final checkpoint and stops the projection
     * subscriber.
     *
     * <p>After this method returns, the state view is frozen — no further events
     * are processed. Existing query methods remain callable but return stale data.</p>
     *
     * <p>This method is idempotent — calling it when the store is already stopped
     * has no effect.</p>
     */
    void stop();
}
