/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

/**
 * Snapshot of storage utilization for health monitoring in the Persistence
 * Layer (Doc 04 §8.6).
 *
 * <p>{@code StorageHealth} provides a point-in-time view of disk usage across
 * both the events database ({@code homesynapse-events.db}) and the telemetry
 * database ({@code homesynapse-telemetry.db}). It is returned by
 * {@link MaintenanceService#getStorageHealth()} and consumed by the
 * Observability subsystem (Doc 11) for health indicators and the REST API
 * (Doc 09) for admin endpoints.</p>
 *
 * <h2>Storage Budget</h2>
 *
 * <p>The {@code budgetBytes} field reflects the configured storage budget.
 * A value of {@code 0} means no budget has been configured and
 * {@code usagePercent} is not meaningful. When a budget is configured,
 * {@code usagePercent} represents {@code totalSizeBytes / budgetBytes * 100}.
 * The {@code healthy} field is {@code false} when usage exceeds the configured
 * threshold (default 90%).</p>
 *
 * @param eventsDatabaseSizeBytes    size of the events database file in bytes
 * @param telemetryDatabaseSizeBytes size of the telemetry database file in bytes
 * @param totalSizeBytes             combined size of both databases in bytes
 * @param budgetBytes                configured storage budget in bytes, or
 *                                   {@code 0} if no budget is configured
 * @param usagePercent               percentage of budget consumed
 *                                   ({@code totalSizeBytes / budgetBytes * 100}),
 *                                   or {@code 0.0} if no budget is configured
 * @param walSizeBytes               current size of the WAL file in bytes
 * @param healthy                    {@code true} if storage utilization is within
 *                                   acceptable thresholds, {@code false} if usage
 *                                   exceeds the configured limit
 *
 * @see MaintenanceService#getStorageHealth()
 * @since 1.0
 */
public record StorageHealth(
        long eventsDatabaseSizeBytes,
        long telemetryDatabaseSizeBytes,
        long totalSizeBytes,
        long budgetBytes,
        double usagePercent,
        long walSizeBytes,
        boolean healthy
) { }
