/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

/**
 * Configuration for a single {@link MigrationRunner#migrate} invocation.
 *
 * <p>Three factory methods cover the canonical operating modes:
 * {@link #freshInstall()} for first-boot and test scenarios,
 * {@link #upgrade(boolean)} for production upgrades where a pre-migration
 * backup is mandatory (LTD-07, LTD-14), and {@link #recovery()} for
 * development or operator-driven recovery when a previous migration
 * attempt failed and must be re-attempted after the underlying SQL has
 * been corrected.</p>
 *
 * @param backupRequired   if {@code true}, pending migrations on a database
 *                         that already contains applied migrations require
 *                         {@code backupVerified == true} to proceed
 * @param backupVerified   signals that a pre-migration backup has been taken
 *                         and verified by the caller
 * @param forceRetryFailed if {@code true}, re-attempts migrations previously
 *                         recorded with {@code success=0}. Intended for
 *                         development and operator recovery only.
 */
record MigrationConfig(boolean backupRequired, boolean backupVerified, boolean forceRetryFailed) {

    /** For fresh installs and testing — no backup checks, no force retry. */
    static MigrationConfig freshInstall() {
        return new MigrationConfig(false, false, false);
    }

    /** For production upgrades — backup is mandatory (LTD-07, LTD-14). */
    static MigrationConfig upgrade(boolean backupVerified) {
        return new MigrationConfig(true, backupVerified, false);
    }

    /** For recovery scenarios — retries previously failed migrations. */
    static MigrationConfig recovery() {
        return new MigrationConfig(false, false, true);
    }
}
