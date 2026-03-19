/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

/**
 * Configures backup creation behavior for the Persistence Layer (Doc 04 §8.4).
 *
 * <p>{@code BackupOptions} controls which databases are included in a backup
 * and whether the backup is a safety snapshot taken before a schema migration
 * or upgrade. Passed to
 * {@link PersistenceLifecycle#createBackup(BackupOptions)}.</p>
 *
 * <h2>Telemetry Inclusion</h2>
 *
 * <p>When {@code includeTelemetry} is {@code true}, the backup includes both
 * {@code homesynapse-events.db} and {@code homesynapse-telemetry.db}. When
 * {@code false}, only the events database is backed up — the telemetry ring
 * store is excluded to save space, since telemetry data is ephemeral by
 * design.</p>
 *
 * @param includeTelemetry whether to include the telemetry ring store database
 *                         ({@code homesynapse-telemetry.db}) in the backup
 * @param preUpgrade       whether this backup is a pre-upgrade safety snapshot,
 *                         taken automatically before schema migrations
 *
 * @see PersistenceLifecycle#createBackup(BackupOptions)
 * @see BackupResult
 * @since 1.0
 */
public record BackupOptions(
        boolean includeTelemetry,
        boolean preUpgrade
) { }
