/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

/**
 * Storage maintenance operations for the Persistence Layer (Doc 04 §8.6).
 *
 * <p>{@code MaintenanceService} exposes retention enforcement, incremental
 * vacuum, and storage health monitoring. It is consumed by the Observability
 * subsystem (Doc 11) for health checks and by the REST API (Doc 09) for
 * admin endpoints.</p>
 *
 * <h2>Retention Policy</h2>
 *
 * <p>Retention is priority-based. Events are deleted in order of priority
 * tier when they exceed their configured retention period:</p>
 * <ol>
 *   <li>{@code DIAGNOSTIC} events expire first (default 7 days)</li>
 *   <li>{@code NORMAL} events expire next (default 90 days)</li>
 *   <li>{@code CRITICAL} events expire last (default 365 days)</li>
 * </ol>
 *
 * <p>The retention sweep is interruptible — it yields every
 * {@code batch_size} deletions (configurable per Doc 04 §9) to avoid
 * holding the write lock for extended periods.</p>
 *
 * <h2>Incremental Vacuum</h2>
 *
 * <p>After retention deletes rows, the freed pages remain allocated in the
 * database file. {@link #runVacuum()} reclaims this space using SQLite's
 * incremental vacuum feature (Doc 04 §3.7), which frees pages without
 * rebuilding the entire database.</p>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>Implementations must be safe for concurrent use. The Observability
 * subsystem may call {@link #getStorageHealth()} concurrently with a
 * running retention sweep or vacuum operation.</p>
 *
 * @see RetentionResult
 * @see VacuumResult
 * @see StorageHealth
 * @since 1.0
 */
public interface MaintenanceService {

    /**
     * Executes a priority-based event retention sweep.
     *
     * <p>Deletes events that exceed their priority tier's retention period.
     * The sweep processes events in priority order: {@code DIAGNOSTIC} first,
     * then {@code NORMAL}, then {@code CRITICAL}. The sweep is interruptible
     * and yields every batch to avoid prolonged write lock contention.</p>
     *
     * @return the retention result documenting how many events were deleted
     *         per priority tier, never {@code null}
     */
    RetentionResult runRetention();

    /**
     * Executes an incremental vacuum operation to reclaim disk space.
     *
     * <p>Frees database pages that were allocated to deleted rows. Unlike a
     * full {@code VACUUM}, incremental vacuum does not rebuild the entire
     * database — it only returns freed pages to the filesystem.</p>
     *
     * @return the vacuum result documenting pages freed and size reduction,
     *         never {@code null}
     */
    VacuumResult runVacuum();

    /**
     * Returns a snapshot of current storage utilization.
     *
     * <p>The snapshot includes individual and combined database sizes, WAL
     * file size, budget utilization percentage, and an overall health
     * assessment. This is a read-only diagnostic operation.</p>
     *
     * @return the current storage health snapshot, never {@code null}
     */
    StorageHealth getStorageHealth();
}
