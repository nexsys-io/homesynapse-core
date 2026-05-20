/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * Lifecycle management interface for the Persistence Layer subsystem
 * (Doc 04 §8.4).
 *
 * <p>{@code PersistenceLifecycle} controls the initialization, shutdown,
 * backup, and restore of the HomeSynapse storage infrastructure. It is
 * consumed by the Startup &amp; Lifecycle subsystem (Doc 12) which coordinates
 * ordered initialization of all HomeSynapse subsystems.</p>
 *
 * <p><strong>Virtual Thread Safety:</strong> All implementations MUST route
 * storage operations through a platform thread executor, not virtual threads.
 * See Virtual Thread Risk Audit finding B-4.</p>
 *
 * <h2>Startup Sequence</h2>
 *
 * <p>When {@link #start()} is called, the Persistence Layer:</p>
 * <ol>
 *   <li>Opens databases, runs migrations, prepares connections</li>
 *   <li>Recovers any incomplete checkpoints from the previous session</li>
 *   <li>Completes the returned {@link CompletableFuture} when all storage is
 *       initialized and ready for use</li>
 * </ol>
 *
 * <h2>Boot Order</h2>
 *
 * <p>The Persistence Layer starts <em>before</em> the Event Bus, State Store,
 * and all other subscribers (Doc 12 §3.1). It must be ready before any events
 * can be published or checkpoints can be read. Other subsystems' startup
 * methods should not be called until the future returned by {@code start()}
 * completes.</p>
 *
 * <h2>Shutdown</h2>
 *
 * <p>When {@link #stop()} is called, the Persistence Layer closes connections,
 * flushes pending writes, and releases resources. After {@code stop()}
 * returns, no further storage operations are possible.</p>
 *
 * @see BackupOptions
 * @see BackupResult
 * @see com.homesynapse.state.StateStoreLifecycle
 * @since 1.0
 */
public interface PersistenceLifecycle {

    /**
     * Opens databases, runs migrations, prepares connections.
     *
     * <p>The returned future completes normally when all storage is ready
     * for use. The future completes exceptionally if storage initialization
     * or migration fails irrecoverably.</p>
     *
     * <p>This method is idempotent — calling it when the persistence layer
     * is already started has no effect and returns an already-completed
     * future.</p>
     *
     * @return a future that completes when storage is ready for use, never
     *         {@code null}
     */
    CompletableFuture<Void> start();

    /**
     * Closes connections, flushes pending writes, and releases resources.
     *
     * <p>After this method returns, no further storage operations are
     * possible. Any in-progress writes will have been flushed to durable
     * storage.</p>
     *
     * <p>This method is idempotent — calling it when the persistence layer
     * is already stopped has no effect.</p>
     */
    void stop();

    /**
     * Creates a timestamped backup of the HomeSynapse storage.
     *
     * <p>The backup is created using a hot-backup mechanism — the live
     * storage remains fully operational during the backup. After copying,
     * an integrity check is run on the backup copy to verify consistency
     * (the result is reported in {@link BackupResult#integrityVerified()}).</p>
     *
     * @param options backup configuration controlling telemetry inclusion
     *                and pre-upgrade tagging, never {@code null}
     * @return the backup result including directory location and integrity
     *         status, never {@code null}
     * @throws NullPointerException if {@code options} is {@code null}
     */
    BackupResult createBackup(BackupOptions options);

    /**
     * Restores storage from a previously created backup directory.
     *
     * <p>The persistence layer must be stopped before calling this method.
     * After restore completes, {@link #start()} must be called to
     * reinitialize storage from the restored state.</p>
     *
     * @param backupDirectory the path to the backup directory created by
     *                        {@link #createBackup(BackupOptions)},
     *                        never {@code null}
     * @throws NullPointerException     if {@code backupDirectory} is
     *                                  {@code null}
     * @throws IllegalStateException    if the persistence layer is currently
     *                                  running (must be stopped first)
     * @throws IllegalArgumentException if {@code backupDirectory} does not
     *                                  contain valid backup files
     */
    void restoreFromBackup(Path backupDirectory);
}
