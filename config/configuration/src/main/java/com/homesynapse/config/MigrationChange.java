/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.config;

import java.util.Objects;

/**
 * A single modification applied by a {@link ConfigMigrator} during schema
 * migration (Doc 06 §3.7).
 *
 * <p>Each {@code MigrationChange} describes one atomic transformation that a
 * migrator performed on the raw YAML map when upgrading from one schema
 * version to the next. Changes are collected into a {@link MigrationResult}
 * and presented in a {@link MigrationPreview} for dry-run review.</p>
 *
 * <p>This type was renamed from {@code ConfigChange} (Doc 06 §3.7) to avoid
 * a naming collision with the reload-path {@link ConfigChange} (Doc 06 §4.3).</p>
 *
 * @param type     the kind of modification applied; never {@code null}
 * @param path     the dotted configuration path affected by the change;
 *                 never {@code null}
 * @param oldValue the value before migration, or {@code null} for newly added keys
 * @param newValue the value after migration, or {@code null} for removed keys
 * @param reason   a human-readable explanation of why this change was made;
 *                 never {@code null}
 *
 * @see ChangeType
 * @see ConfigMigrator
 * @see MigrationResult
 * @see MigrationPreview
 */
public record MigrationChange(
        ChangeType type,
        String path,
        Object oldValue,
        Object newValue,
        String reason
) {

    /**
     * Validates that required fields are non-null.
     */
    public MigrationChange {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
    }
}
