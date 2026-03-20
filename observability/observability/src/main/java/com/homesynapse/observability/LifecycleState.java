/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

package com.homesynapse.observability;

/**
 * System-wide lifecycle state governing health aggregation behavior.
 *
 * <p>The system transitions through three distinct phases during its lifetime
 * (Doc 11 §3.3, decision D-04). Transitions are one-directional: STARTING →
 * RUNNING → SHUTTING_DOWN. No reverse transitions are permitted.</p>
 *
 * @see SystemHealth
 * @see HealthAggregator
 */
public enum LifecycleState {
    /**
     * System is initializing and subsystems are starting up.
     *
     * <p>During this phase, per-subsystem startup grace periods apply. A
     * subsystem reporting {@link HealthStatus#DEGRADED} within its configured
     * grace period is excluded from aggregate composition. Once the grace period
     * expires or the subsystem reports {@link HealthStatus#HEALTHY}, normal
     * aggregation rules apply. This prevents temporary startup hiccups
     * (e.g., database warming) from making the system appear DEGRADED during
     * boot.</p>
     */
    STARTING,

    /**
     * System is running and all normal health aggregation rules apply.
     *
     * <p>All subsystems are expected to be operational. No grace periods apply.
     * The system-wide health is computed according to tier rules from
     * {@link HealthTier}.</p>
     */
    RUNNING,

    /**
     * System is shutting down.
     *
     * <p>Health transitions are still tracked for diagnostic purposes, but the
     * system is expected to degrade. Background tasks may slow or stop. External
     * integrations may disconnect. The final system health snapshot is captured
     * for post-shutdown diagnostics.</p>
     */
    SHUTTING_DOWN
}
