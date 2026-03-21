/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.observability;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Composes per-subsystem health indicators into a tiered system health model.
 *
 * <p>This interface defines the contract for the health aggregation engine
 * (Doc 11 §3.3, §8.1–§8.2). The aggregator evaluates reactively — triggered
 * by health-change events from subsystems via {@link #onHealthChange(String, HealthStatus, String)},
 * not by polling on a timer. Health evaluation is deterministic and reproducible
 * (INV-TO-02): given the same set of per-subsystem health states, the aggregator
 * produces the same system health result.</p>
 *
 * <p>The aggregation algorithm applies three-tier worst-of composition:
 * <ul>
 *   <li>Tier 1 (CRITICAL_INFRASTRUCTURE): Any UNHEALTHY → system UNHEALTHY;
 *       any DEGRADED → system DEGRADED.</li>
 *   <li>Tier 2 (CORE_SERVICES): ≥2 DEGRADED or any UNHEALTHY → system DEGRADED.</li>
 *   <li>Tier 3 (INTERFACE_SERVICES): All UNHEALTHY → system DEGRADED.</li>
 * </ul>
 * <p>System-wide result is worst-of across tier results (UNHEALTHY > DEGRADED > HEALTHY).</p>
 *
 * @see HealthContributor
 * @see SystemHealth
 * @see HealthTier
 * @see HealthStatus
 */
public interface HealthAggregator {
    /**
     * Returns the current complete system health snapshot.
     *
     * <p>Includes tier breakdown and per-subsystem detail. Returned object is
     * immutable and consistent at the time of the call. No I/O is performed.
     * Must complete within 1 ms on Pi 5 for all O(10) subsystem lookups
     * (Doc 11 §10).</p>
     *
     * <p>This is the data structure returned by the REST API's
     * {@code GET /api/v1/system/health} endpoint (Doc 09) and consumed by the
     * Web UI's health dashboard (Doc 13 §3.8).</p>
     *
     * @return the current system health snapshot. Non-null.
     *
     * @see SystemHealth
     * @see HealthTier
     */
    SystemHealth getSystemHealth();

    /**
     * Returns health for a specific subsystem, or empty if not registered.
     *
     * <p>Thread-safe. Returns an immutable snapshot.</p>
     *
     * @param subsystemId the subsystem identifier string (e.g., "event-bus",
     *        "state-store"). Non-null.
     * @return {@code Optional} containing the subsystem's current health, or
     *         empty if the subsystem is not registered or has never reported
     *         health.
     *
     * @throws NullPointerException if subsystemId is null
     *
     * @see SubsystemHealth
     */
    Optional<SubsystemHealth> getSubsystemHealth(String subsystemId);

    /**
     * Called by {@link HealthContributor} when a subsystem's health state changes.
     *
     * <p>Triggers re-evaluation of tier and system-wide health according to the
     * three-tier composition algorithm. Each call produces a {@code HealthTransitionEvent}
     * JFR event and a structured log entry (INV-TO-04). This is the primary
     * data path for health updates — reactive and event-driven, not polling.</p>
     *
     * @param subsystemId the reporting subsystem's identifier string (e.g.,
     *        "event-bus"). Non-null. Tier classification is performed by
     *        looking up this ID in the tier assignment configuration.
     * @param status the subsystem's current health status. Non-null.
     * @param reason human-readable explanation of the status (e.g., "JFR
     *        recording stalled: no flush in 45 seconds"). Non-null.
     *
     * @throws NullPointerException if subsystemId, status, or reason is null
     *
     * @see HealthContributor#reportHealth(HealthStatus, String)
     * @see HealthTier
     */
    void onHealthChange(String subsystemId, HealthStatus status, String reason);

    /**
     * Returns health state transitions in a given time range.
     *
     * <p>Provides historical health data for diagnostics and trend analysis.
     * Results are ordered by timestamp ascending (earliest first).</p>
     *
     * <p>Each entry represents a state transition event, not a point-in-time
     * snapshot. The list may be empty if no transitions occurred in the given
     * range.</p>
     *
     * @param since the start of the time range (inclusive). Non-null.
     * @param until the end of the time range (inclusive). Non-null. May be before
     *        {@code since}, in which case an empty list is returned.
     * @return an unmodifiable list of health transitions in the time range,
     *         ordered by timestamp ascending. Non-null, may be empty.
     *
     * @throws NullPointerException if since or until is null
     *
     * @see SubsystemHealth
     */
    List<SubsystemHealth> getHealthHistory(Instant since, Instant until);
}
