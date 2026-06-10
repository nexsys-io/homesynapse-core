/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.config;

import java.util.List;
import java.util.Objects;

/**
 * A dry-run report of migration changes without modifying the configuration
 * file (Doc 06 §3.7, AMD-67).
 *
 * <p>A {@code MigrationPreview} is produced by the migration pipeline to show
 * the operator what changes would be applied when migrating from one
 * {@code (major, minor)} schema version to another. The
 * {@link #requiresUserReview()} flag indicates whether any of the planned
 * changes are lossy (key removals, value transformations) and therefore
 * require explicit operator confirmation before proceeding.</p>
 *
 * @param fromMajor          the source schema major version; must be {@code >= 1}
 * @param fromMinor          the source schema minor version; must be {@code >= 0}
 * @param toMajor            the target schema major version; must be {@code >= 1}
 * @param toMinor            the target schema minor version; must be {@code >= 0}
 * @param plannedChanges     the list of changes that would be applied,
 *                           unmodifiable; never {@code null}
 * @param requiresUserReview {@code true} if any planned change removes keys
 *                           or transforms values in lossy ways
 *
 * @see ConfigMigrator
 * @see MigrationChange
 */
public record MigrationPreview(
        int fromMajor,
        int fromMinor,
        int toMajor,
        int toMinor,
        List<MigrationChange> plannedChanges,
        boolean requiresUserReview
) {

    /**
     * Validates the schema-version pairs, validates that the planned changes
     * list is non-null, and makes it unmodifiable.
     */
    public MigrationPreview {
        if (fromMajor < 1) {
            throw new IllegalArgumentException(
                    "fromMajor must be >= 1: " + fromMajor);
        }
        if (fromMinor < 0) {
            throw new IllegalArgumentException(
                    "fromMinor must be >= 0: " + fromMinor);
        }
        if (toMajor < 1) {
            throw new IllegalArgumentException(
                    "toMajor must be >= 1: " + toMajor);
        }
        if (toMinor < 0) {
            throw new IllegalArgumentException(
                    "toMinor must be >= 0: " + toMinor);
        }
        Objects.requireNonNull(plannedChanges, "plannedChanges must not be null");
        plannedChanges = List.copyOf(plannedChanges);
    }
}
