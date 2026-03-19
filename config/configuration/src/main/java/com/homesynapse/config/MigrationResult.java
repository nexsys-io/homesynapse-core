/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.config;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The output of a single {@link ConfigMigrator#migrate(Map)} invocation
 * (Doc 06 §3.7).
 *
 * <p>A {@code MigrationResult} contains the transformed raw YAML map after
 * the migration has been applied and the list of individual changes that
 * were made. Both the migrated map and the change list are unmodifiable.</p>
 *
 * @param migratedConfig the raw YAML map after migration, unmodifiable;
 *                       never {@code null}
 * @param changes        the list of individual modifications applied,
 *                       unmodifiable; never {@code null}
 *
 * @see ConfigMigrator
 * @see MigrationChange
 */
public record MigrationResult(
        Map<String, Object> migratedConfig,
        List<MigrationChange> changes
) {

    /**
     * Validates that all fields are non-null and makes both collections
     * unmodifiable.
     */
    public MigrationResult {
        Objects.requireNonNull(migratedConfig, "migratedConfig must not be null");
        Objects.requireNonNull(changes, "changes must not be null");
        migratedConfig = Map.copyOf(migratedConfig);
        changes = List.copyOf(changes);
    }
}
