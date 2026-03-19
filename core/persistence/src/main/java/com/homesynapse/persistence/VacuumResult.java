/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

/**
 * Result of an incremental vacuum operation in the Persistence Layer
 * (Doc 04 §8.6).
 *
 * <p>{@code VacuumResult} documents the outcome of
 * {@link MaintenanceService#runVacuum()}, reporting how many database pages
 * were freed and the before/after database size. Incremental vacuum reclaims
 * disk space from deleted rows without requiring a full database rebuild
 * (Doc 04 §3.7).</p>
 *
 * @param pagesFreed              number of SQLite database pages reclaimed
 * @param databaseSizeBeforeBytes total database file size before vacuum, in
 *                                bytes
 * @param databaseSizeAfterBytes  total database file size after vacuum, in
 *                                bytes
 * @param durationMs              wall-clock duration of the vacuum operation
 *                                in milliseconds
 *
 * @see MaintenanceService#runVacuum()
 * @since 1.0
 */
public record VacuumResult(
        long pagesFreed,
        long databaseSizeBeforeBytes,
        long databaseSizeAfterBytes,
        long durationMs
) { }
