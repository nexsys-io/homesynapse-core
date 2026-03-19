/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.config;

import java.util.Map;

/**
 * Forward-only migration from one configuration schema version to the next
 * (Doc 06 §3.7).
 *
 * <p>Migrations form a linear chain (1→2, 2→3, etc.). Each
 * {@code ConfigMigrator} operates on the raw YAML map (parsed but not yet
 * validated) and transforms it to conform to the target schema version.
 * The migration pipeline chains migrators in sequence when upgrading across
 * multiple versions.</p>
 *
 * <h2>Idempotency</h2>
 *
 * <p>Implementations must be idempotent — applying the same migration twice
 * to the same input produces the same output. This ensures safe retry
 * behaviour if the migration pipeline is interrupted.</p>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>Implementations are thread-safe. The {@link #migrate(Map)} method
 * must not modify the input map — it returns a new map via
 * {@link MigrationResult}.</p>
 *
 * @see MigrationResult
 * @see MigrationChange
 * @see MigrationPreview
 * @see ChangeType
 */
public interface ConfigMigrator {

    /**
     * Returns the source schema version that this migrator upgrades from.
     *
     * @return the source schema version number
     */
    int fromVersion();

    /**
     * Returns the target schema version that this migrator upgrades to.
     *
     * @return the target schema version number
     */
    int toVersion();

    /**
     * Applies the migration to the raw YAML map and returns the result.
     *
     * <p>The input map must not be modified. The returned
     * {@link MigrationResult} contains a new map with the migration applied
     * and a list of all changes that were made.</p>
     *
     * @param rawConfig the raw parsed YAML configuration map;
     *                  never {@code null}
     * @return the migration result containing the transformed map and
     *         change list; never {@code null}
     */
    MigrationResult migrate(Map<String, Object> rawConfig);
}
