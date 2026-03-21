/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.observability;

import java.time.Instant;
import java.util.Objects;

/**
 * Individual subsystem health snapshot within the aggregated system health model.
 *
 * <p>This record captures the health state and metadata for a single subsystem
 * at a point in time (Doc 11 §4.1). Each subsystem reports its health via a
 * {@link HealthContributor}, which triggers aggregation updates.</p>
 */
public record SubsystemHealth(
    /**
     * Subsystem identifier string (e.g., "event-bus", "state-store", "automation-engine").
     *
     * <p>This is NOT a typed ULID — subsystem identifiers are compile-time string
     * constants. The identifier is used for tier classification and health history
     * tracking.</p>
     */
    String subsystemId,

    /**
     * The health tier this subsystem is assigned to.
     *
     * <p>Per {@link HealthTier} decision D-03, each subsystem is statically
     * assigned to one of three tiers, governing its participation in the
     * tiered aggregation algorithm.</p>
     */
    HealthTier tier,

    /**
     * Current health status as reported by the subsystem via {@link HealthContributor}.
     *
     * <p>Transitions on every status change event. Updates are reactive, not
     * polling-based (INV-TO-01).</p>
     */
    HealthStatus status,

    /**
     * Timestamp when the subsystem entered its current status.
     *
     * <p>Advances on every status transition. Used to calculate how long the
     * subsystem has been in its current state (useful for degradation duration
     * tracking and timeout logic).</p>
     */
    Instant since,

    /**
     * Human-readable reason string provided by the subsystem explaining its current status.
     *
     * <p>Examples: "JFR recording stalled: no flush in 45 seconds", "database
     * connection failed: timeout", "lock acquisition failed after 5 retries".</p>
     */
    String reason,

    /**
     * True if the subsystem is still within its startup grace period.
     *
     * <p>During {@link LifecycleState#STARTING}, subsystems may report
     * {@link HealthStatus#DEGRADED} without affecting the system health until
     * the grace period expires. Once in grace period expires or the subsystem
     * reports {@link HealthStatus#HEALTHY}, this flag becomes {@code false}
     * and normal aggregation rules apply.</p>
     */
    boolean inGracePeriod
) {
    /**
     * Compact constructor validating all non-null fields.
     *
     * @throws NullPointerException if subsystemId, tier, status, since, or reason is null
     */
    public SubsystemHealth {
        Objects.requireNonNull(subsystemId, "subsystemId cannot be null");
        Objects.requireNonNull(tier, "tier cannot be null");
        Objects.requireNonNull(status, "status cannot be null");
        Objects.requireNonNull(since, "since cannot be null");
        Objects.requireNonNull(reason, "reason cannot be null");
    }
}
