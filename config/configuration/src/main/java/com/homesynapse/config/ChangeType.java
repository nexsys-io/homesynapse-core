/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.config;

/**
 * Classifies the kind of modification applied by a {@link ConfigMigrator}
 * during schema migration (Doc 06 §3.7).
 *
 * <p>Each {@link MigrationChange} carries a {@code ChangeType} that describes
 * what the migration step did to the raw YAML map. This classification drives
 * the {@link MigrationPreview#requiresUserReview()} flag — lossy changes
 * ({@link #KEY_REMOVED}, {@link #VALUE_TRANSFORMED}) require user review
 * before the migration is applied.</p>
 *
 * @see MigrationChange
 * @see ConfigMigrator
 */
public enum ChangeType {

    /** A configuration key was renamed (old key removed, new key added with the same value). */
    KEY_RENAMED,

    /** A new configuration key was added with a default value. */
    KEY_ADDED,

    /** A configuration key was removed. This is a lossy change. */
    KEY_REMOVED,

    /** A configuration value was transformed (e.g., unit conversion, format change). This is a lossy change. */
    VALUE_TRANSFORMED,

    /** An entire configuration section was restructured (keys moved, nested differently). */
    SECTION_RESTRUCTURED
}
