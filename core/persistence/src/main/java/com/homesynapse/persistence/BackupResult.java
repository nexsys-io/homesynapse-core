/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/**
 * Result of a backup creation operation in the Persistence Layer (Doc 04 §8.4).
 *
 * <p>{@code BackupResult} captures the outcome of
 * {@link PersistenceLifecycle#createBackup(BackupOptions)}, including the
 * location of the backup directory, the event log position at backup time,
 * and whether integrity verification passed on the backup copy.</p>
 *
 * <h2>Integrity Verification</h2>
 *
 * <p>The {@code integrityVerified} field indicates whether
 * {@code PRAGMA integrity_check} passed on the <em>backup copy</em>, not
 * on the live database. The backup process runs integrity check on the
 * copied file to catch any corruption that may have occurred during the
 * hot backup.</p>
 *
 * @param backupDirectory     the filesystem path to the backup directory,
 *                            never {@code null}
 * @param createdAt           the wall-clock time when the backup was created,
 *                            never {@code null}
 * @param eventsGlobalPosition the global event log position at backup time,
 *                            enabling point-in-time correlation
 * @param telemetryIncluded   whether the telemetry ring store database was
 *                            included in the backup
 * @param integrityVerified   whether {@code PRAGMA integrity_check} passed
 *                            on the backup copy
 *
 * @see PersistenceLifecycle#createBackup(BackupOptions)
 * @see BackupOptions
 * @since 1.0
 */
public record BackupResult(
        Path backupDirectory,
        Instant createdAt,
        long eventsGlobalPosition,
        boolean telemetryIncluded,
        boolean integrityVerified
) {

    /**
     * Validates that all required fields are non-null.
     */
    public BackupResult {
        Objects.requireNonNull(backupDirectory, "backupDirectory must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}
