/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.config;

import java.util.List;
import java.util.Objects;

/**
 * A dry-run report of migration changes without modifying the configuration
 * file (Doc 06 §3.7).
 *
 * <p>A {@code MigrationPreview} is produced by the migration pipeline to show
 * the operator what changes would be applied when migrating from one schema
 * version to another. The {@link #requiresUserReview()} flag indicates
 * whether any of the planned changes are lossy (key removals, value
 * transformations) and therefore require explicit operator confirmation
 * before proceeding.</p>
 *
 * @param fromVersion       the source schema version; must be non-negative
 * @param toVersion         the target schema version; must be greater than
 *                          {@code fromVersion}
 * @param plannedChanges    the list of changes that would be applied,
 *                          unmodifiable; never {@code null}
 * @param requiresUserReview {@code true} if any planned change removes keys
 *                          or transforms values in lossy ways
 *
 * @see ConfigMigrator
 * @see MigrationChange
 */
public record MigrationPreview(
        int fromVersion,
        int toVersion,
        List<MigrationChange> plannedChanges,
        boolean requiresUserReview
) {

    /**
     * Validates that the planned changes list is non-null and makes it
     * unmodifiable.
     */
    public MigrationPreview {
        Objects.requireNonNull(plannedChanges, "plannedChanges must not be null");
        plannedChanges = List.copyOf(plannedChanges);
    }
}
