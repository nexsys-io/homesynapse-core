/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.observability;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Complete system health snapshot with tiered breakdown and per-subsystem detail.
 *
 * <p>This is the top-level health data structure returned by
 * {@link HealthAggregator#getSystemHealth()} and consumed by:
 * <ul>
 *   <li>The REST API's {@code /api/v1/system/health} endpoint (Doc 09)</li>
 *   <li>The Web UI's three-tier health dashboard (Doc 13 §3.8)</li>
 *   <li>The Startup/Lifecycle module for boot sequencing (Doc 12)</li>
 * </ul>
 * <p>All fields are immutable and defensively copied. The snapshot is a moment-in-time
 * view consistent at the time of the call (Doc 11 §4.1).</p>
 *
 * @see HealthAggregator#getSystemHealth()
 * @see HealthTier
 * @see SubsystemHealth
 */
public record SystemHealth(
    /**
     * System-wide health status.
     *
     * <p>Computed as the worst-of across all tier results according to the
     * three-tier aggregation algorithm (Doc 11 §3.3, decision D-02). The result
     * is {@link HealthStatus#UNHEALTHY} if any Tier 1 subsystem is UNHEALTHY,
     * {@link HealthStatus#DEGRADED} if any Tier 1 is DEGRADED or Tier 2 has
     * multiple DEGRADED subsystems, else {@link HealthStatus#HEALTHY}.</p>
     */
    HealthStatus status,

    /**
     * Current system lifecycle state.
     *
     * <p>Governs whether per-subsystem grace periods apply (during
     * {@link LifecycleState#STARTING}) and whether the system is still
     * accepting work ({@link LifecycleState#RUNNING}) or winding down
     * ({@link LifecycleState#SHUTTING_DOWN}).</p>
     */
    LifecycleState lifecycle,

    /**
     * Timestamp when the system entered its current health status.
     *
     * <p>Advances on every system-wide status transition. Used to track how
     * long the system has been HEALTHY, DEGRADED, or UNHEALTHY.</p>
     */
    Instant since,

    /**
     * Human-readable reason explaining the current system status.
     *
     * <p>Examples: "Tier 1 degraded: state-store reporting DEGRADED",
     * "Tier 2 multiple failures: automation-engine, integration-runtime both DEGRADED",
     * "All tiers healthy".</p>
     */
    String reason,

    /**
     * Per-tier health summaries keyed by {@link HealthTier}.
     *
     * <p>A complete {@link HealthTier} entry for each of the three tiers
     * (CRITICAL_INFRASTRUCTURE, CORE_SERVICES, INTERFACE_SERVICES). Non-null,
     * non-empty map with exactly three entries.</p>
     */
    Map<HealthTier, TierHealth> tiers,

    /**
     * Per-subsystem health details keyed by subsystem ID string.
     *
     * <p>A complete {@link SubsystemHealth} entry for each registered subsystem.
     * Non-null. Keys are subsystem identifier strings ("event-bus", "state-store",
     * etc.); values are the most recent health snapshot reported by that subsystem
     * via {@link HealthContributor}.</p>
     */
    Map<String, SubsystemHealth> subsystems
) {
    /**
     * Compact constructor validating all fields and applying defensive copies.
     *
     * @throws NullPointerException if any field is null
     */
    public SystemHealth {
        Objects.requireNonNull(status, "status cannot be null");
        Objects.requireNonNull(lifecycle, "lifecycle cannot be null");
        Objects.requireNonNull(since, "since cannot be null");
        Objects.requireNonNull(reason, "reason cannot be null");
        Objects.requireNonNull(tiers, "tiers cannot be null");
        Objects.requireNonNull(subsystems, "subsystems cannot be null");
        tiers = Map.copyOf(tiers);
        subsystems = Map.copyOf(subsystems);
    }
}
