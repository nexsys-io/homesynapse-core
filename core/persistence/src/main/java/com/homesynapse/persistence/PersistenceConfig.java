/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import java.util.Objects;

/**
 * Persistence layer configuration, combining a {@link DeploymentProfile} with
 * operator overrides loaded from YAML.
 *
 * <p>The profile provides pre-validated PRAGMA defaults. Operator overrides
 * (via {@code persistence.*} YAML keys) take precedence when explicitly set.
 * If no overrides are provided, the profile's values are used as-is.
 *
 * <p>This record is the Phase 2 type. Phase 3 will add a factory method that
 * reads from the configuration system, applies auto-detected hardware profile
 * as the base, and overlays operator overrides. The factory belongs in the
 * persistence module's internal layer (alongside other configuration loaders)
 * and is not Phase 2 scope.
 *
 * @param profile         the hardware deployment profile (auto-detected or
 *                        configured, non-null)
 * @param retentionPolicy per-priority retention durations (overridable via
 *                        YAML, non-null)
 */
public record PersistenceConfig(
        DeploymentProfile profile,
        RetentionPolicy retentionPolicy
) {

    /**
     * Default configuration using the {@link DeploymentProfile#HOME} profile
     * and {@link RetentionPolicy#SOURCE_DEFAULT}.
     *
     * <p>This constant is suitable for development, tests, and any deployment
     * where hardware auto-detection is not yet wired in.
     */
    public static final PersistenceConfig HOME_DEFAULT =
            new PersistenceConfig(
                    DeploymentProfile.HOME, RetentionPolicy.SOURCE_DEFAULT);

    /**
     * Validates the record components.
     *
     * @throws NullPointerException if either component is null
     */
    public PersistenceConfig {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(retentionPolicy, "retentionPolicy");
    }
}
