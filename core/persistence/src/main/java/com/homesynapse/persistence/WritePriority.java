/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

/**
 * Priority tiers for write operations submitted to the {@link WriteCoordinator}.
 *
 * <p>Lower ordinal values indicate higher priority. When multiple writes are
 * queued, the coordinator services them in priority order to ensure that
 * event publishing (the most latency-sensitive path) is never starved by
 * lower-priority maintenance operations.</p>
 *
 * <p>The five tiers reflect the operational priorities of the Persistence Layer
 * (Doc 04 §3, AMD-06):</p>
 * <ol>
 *   <li>{@link #EVENT_PUBLISH} — user-facing latency path, highest priority</li>
 *   <li>{@link #STATE_PROJECTION} — state consistency, near-real-time</li>
 *   <li>{@link #WAL_CHECKPOINT} — durability housekeeping</li>
 *   <li>{@link #RETENTION} — background cleanup, batch deletes</li>
 *   <li>{@link #BACKUP} — bulk copy, lowest priority</li>
 * </ol>
 *
 * <p>This enum is package-private — it is an internal implementation detail
 * of the persistence module, not part of the public API.</p>
 *
 * @see WriteCoordinator
 */
enum WritePriority {

    /** Event publishing — highest priority, most latency-sensitive. */
    EVENT_PUBLISH(1),

    /** State projection checkpoint writes. */
    STATE_PROJECTION(2),

    /** WAL checkpoint operations. */
    WAL_CHECKPOINT(3),

    /** Event retention cleanup — batch deletes. */
    RETENTION(4),

    /** Database backup — lowest priority. */
    BACKUP(5);

    private final int rank;

    WritePriority(int rank) {
        this.rank = rank;
    }

    /**
     * Returns the numeric priority rank. Lower values = higher priority.
     *
     * @return the rank value (1 = highest, 5 = lowest)
     */
    int rank() {
        return rank;
    }
}
