/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

/**
 * Thrown when a migration cannot be applied.
 *
 * <p>Possible causes include: a SQL statement failed to execute, the checksum
 * of an already-applied migration no longer matches the on-disk file, a version
 * gap was detected in the pending migration list, the database schema version
 * is ahead of the application's known migrations, a previously failed migration
 * was encountered without {@code forceRetryFailed} enabled, or a backup was
 * required but not verified.</p>
 *
 * <p>This is a {@link RuntimeException} rather than a checked exception because
 * migration failures are fatal — {@code PersistenceLifecycle.start()} aborts
 * the persistence layer boot on any {@code MigrationException}, and there is
 * no meaningful recovery path inside the startup sequence itself.</p>
 */
final class MigrationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    MigrationException(String message) {
        super(message);
    }

    MigrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
