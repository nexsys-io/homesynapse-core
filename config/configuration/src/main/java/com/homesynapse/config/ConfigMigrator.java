/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.config;

import java.util.Map;

/**
 * Forward-only migration of the system configuration document from one
 * {@code (major, minor)} schema version to the next (Doc 06 §3.7, AMD-67).
 *
 * <p>Migrations form a linear chain ordered by {@code (major, minor)}
 * (1.0&rarr;2.0, 2.0&rarr;2.1, 2.1&rarr;3.0, etc.). Each
 * {@code ConfigMigrator} operates on the raw YAML map (parsed but not yet
 * validated) and transforms it to conform to the target schema version.
 * The migration pipeline chains migrators in sequence when upgrading across
 * multiple versions.</p>
 *
 * <h2>Migration Trigger (AMD-67-INV-02)</h2>
 *
 * <p>A migrator triggers only on a <em>major</em> mismatch — a persisted
 * {@code configSchemaMajor} lower than the major the loader declares. A
 * minor-only mismatch never migrates: the loader must tolerate older minors
 * within the same major, because minor bumps are additive and
 * backward-compatible by definition.</p>
 *
 * <p>This interface migrates the <em>whole system config document</em>
 * ({@link ConfigModel}). It is a distinct compatibility surface from the
 * per-adapter config-schema pair on {@code IntegrationDescriptor} (AMD-54);
 * no code path derives one from the other (AMD-67-INV-01).</p>
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
     * Returns the source schema major version that this migrator upgrades from.
     *
     * @return the source schema major version; always {@code >= 1}
     */
    int fromMajor();

    /**
     * Returns the source schema minor version that this migrator upgrades from.
     *
     * @return the source schema minor version; always {@code >= 0}
     */
    int fromMinor();

    /**
     * Returns the target schema major version that this migrator upgrades to.
     *
     * @return the target schema major version; always {@code >= 1}
     */
    int toMajor();

    /**
     * Returns the target schema minor version that this migrator upgrades to.
     *
     * @return the target schema minor version; always {@code >= 0}; 0 when
     *         {@link #toMajor()} is a bump over {@link #fromMajor()}
     */
    int toMinor();

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
