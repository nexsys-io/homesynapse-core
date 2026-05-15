/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

/**
 * Result of a single {@link MaintenanceSubscriber#runMaintenance()} pass.
 *
 * <p>Carries the counts and timing data needed for operational observability
 * of retention activity. Logged at INFO at end of each maintenance pass and
 * exposed to JFR / metrics consumers.
 *
 * @param eventsDeleted          total events purged across all bounded
 *                               transactions in this pass (≥ 0)
 * @param batchesExecuted        number of bounded purge transactions run
 *                               (≥ 0; one transaction per
 *                               {@link MaintenanceSubscriber#DEFAULT_PURGE_BATCH_SIZE}
 *                               rows)
 * @param walCheckpointTriggered {@code true} if a WAL checkpoint was triggered
 *                               at the end of the maintenance pass
 * @param durationMs             wall-clock duration of the maintenance pass
 *                               in milliseconds (≥ 0)
 */
public record MaintenanceResult(
        long eventsDeleted,
        int batchesExecuted,
        boolean walCheckpointTriggered,
        long durationMs
) {

    /**
     * Validates the record components.
     *
     * @throws IllegalArgumentException if any numeric component is negative
     */
    public MaintenanceResult {
        if (eventsDeleted < 0) {
            throw new IllegalArgumentException(
                    "eventsDeleted must be non-negative: " + eventsDeleted);
        }
        if (batchesExecuted < 0) {
            throw new IllegalArgumentException(
                    "batchesExecuted must be non-negative: " + batchesExecuted);
        }
        if (durationMs < 0) {
            throw new IllegalArgumentException(
                    "durationMs must be non-negative: " + durationMs);
        }
    }
}
