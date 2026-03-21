/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.observability;

/**
 * Callback interface for per-subsystem health reporting.
 *
 * <p>Each subsystem receives its own {@code HealthContributor} instance from
 * the {@link HealthAggregator} during initialization and calls
 * {@link #reportHealth(HealthStatus, String)} whenever its health state changes
 * (Doc 11 §7.1, §8.1–§8.2). The contributor forwards reports to the aggregator
 * for tier composition. This design keeps subsystems decoupled from the
 * aggregation mechanism — subsystems don't know which tier they're in or how
 * health is computed system-wide.</p>
 *
 * @see HealthAggregator
 * @see SubsystemHealth
 * @see HealthStatus
 */
public interface HealthContributor {
    /**
     * Report the subsystem's current health state.
     *
     * <p>Called by subsystems when their health transitions — not on a timer,
     * reactive and event-driven (INV-TO-01). Each call produces a
     * {@code HealthTransitionEvent} JFR event and a structured log entry
     * (INV-TO-04).</p>
     *
     * <p>The implementation forwards this to
     * {@link HealthAggregator#onHealthChange(String, HealthStatus, String)}
     * internally, passing the subsystem ID that was set at construction time.</p>
     *
     * @param status the subsystem's current health status. Non-null.
     * @param reason human-readable explanation (e.g., "JFR recording stalled:
     *        no flush in 45 seconds", "database connection failed: timeout").
     *        Non-null.
     *
     * @throws NullPointerException if status or reason is null
     *
     * @see HealthStatus
     */
    void reportHealth(HealthStatus status, String reason);

    /**
     * Returns the subsystem identifier string used for tier classification.
     *
     * <p>This value is fixed at construction time and does not change. The same
     * subsystem ID is passed to
     * {@link HealthAggregator#onHealthChange(String, HealthStatus, String)} by
     * the contributor implementation.</p>
     *
     * @return the subsystem identifier string (e.g., "event-bus", "state-store",
     *         "automation-engine"). Non-null.
     *
     * @see HealthTier
     * @see HealthAggregator#onHealthChange(String, HealthStatus, String)
     */
    String getSubsystemId();
}
