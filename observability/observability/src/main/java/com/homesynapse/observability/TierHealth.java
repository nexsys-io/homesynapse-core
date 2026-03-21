/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.observability;

import java.util.List;
import java.util.Objects;

/**
 * Per-tier health summary within the aggregated system health model.
 *
 * <p>This record summarizes the health of all subsystems within a single
 * health tier (Doc 11 §4.1). Tiers are evaluated according to tier-specific
 * aggregation rules defined in {@link HealthTier}.</p>
 *
 * @see HealthTier
 * @see SystemHealth
 * @see HealthAggregator
 */
public record TierHealth(
    /**
     * Which health tier this summary covers.
     *
     * <p>Non-null. Identifies the tier whose aggregation rule was applied to
     * produce this summary.</p>
     */
    HealthTier tier,

    /**
     * The tier's aggregate health status.
     *
     * <p>Computed from tier-specific aggregation rules (see {@link HealthTier}
     * Javadoc). For CRITICAL_INFRASTRUCTURE: any UNHEALTHY → UNHEALTHY, any
     * DEGRADED → DEGRADED. For CORE_SERVICES: ≥2 DEGRADED or any UNHEALTHY →
     * DEGRADED. For INTERFACE_SERVICES: all UNHEALTHY → DEGRADED.</p>
     */
    HealthStatus status,

    /**
     * List of subsystem IDs currently reporting {@link HealthStatus#DEGRADED} within this tier.
     *
     * <p>Non-null, may be empty. Subsystem IDs are the same strings reported
     * via {@link HealthContributor#reportHealth(HealthStatus, String)}.
     * Ordered by subsystem ID for consistent serialization.</p>
     */
    List<String> degradedSubsystems,

    /**
     * List of subsystem IDs currently reporting {@link HealthStatus#UNHEALTHY} within this tier.
     *
     * <p>Non-null, may be empty. Subsystem IDs are the same strings reported
     * via {@link HealthContributor#reportHealth(HealthStatus, String)}.
     * Ordered by subsystem ID for consistent serialization.</p>
     */
    List<String> unhealthySubsystems
) {
    /**
     * Compact constructor validating all fields and applying defensive copies.
     *
     * @throws NullPointerException if any field is null
     */
    public TierHealth {
        Objects.requireNonNull(tier, "tier cannot be null");
        Objects.requireNonNull(status, "status cannot be null");
        Objects.requireNonNull(degradedSubsystems, "degradedSubsystems cannot be null");
        Objects.requireNonNull(unhealthySubsystems, "unhealthySubsystems cannot be null");
        degradedSubsystems = List.copyOf(degradedSubsystems);
        unhealthySubsystems = List.copyOf(unhealthySubsystems);
    }
}
