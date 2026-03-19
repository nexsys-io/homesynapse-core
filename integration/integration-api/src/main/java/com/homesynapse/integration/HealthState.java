/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration;

/**
 * The four-state health model for integration adapters (Doc 05 §3.4, §4.3).
 *
 * <p>Health state transitions are managed by the supervisor's health state machine
 * (integration-runtime, Block O). The enum is in integration-api because adapters
 * reference it when suggesting health transitions via
 * {@link HealthReporter#reportHealthTransition(HealthState, String)}.</p>
 *
 * <p>State transitions follow a defined guard model: HEALTHY → DEGRADED when the
 * health score drops below threshold, DEGRADED → SUSPENDED after
 * {@link HealthParameters#maxDegradedDuration()}, SUSPENDED → FAILED after
 * exhausting probe and restart attempts, and recovery paths back toward HEALTHY
 * when metrics improve. The supervisor enforces transition guards; this enum
 * only defines the states.</p>
 *
 * @see HealthReporter
 * @see HealthParameters
 * @see IntegrationLifecycleEvent
 */
public enum HealthState {

    /**
     * All health metrics are nominal. The adapter is processing events, responding
     * to commands, and heartbeating within expected intervals. Error rates are
     * within the configured sliding window thresholds.
     */
    HEALTHY,

    /**
     * The adapter's health score has dropped below the configured threshold, but
     * it is still operational. The supervisor increases monitoring frequency and
     * begins tracking how long the adapter remains degraded. If the adapter does
     * not recover within {@link HealthParameters#maxDegradedDuration()}, it
     * transitions to {@link #SUSPENDED}.
     */
    DEGRADED,

    /**
     * The adapter has been stopped by the supervisor for recovery probing. The
     * supervisor periodically probes the adapter to check if the underlying
     * condition has resolved. If probes succeed, the adapter transitions back
     * to {@link #HEALTHY}. If probes exhaust the configured cycles
     * ({@link HealthParameters#maxSuspensionCycles()}), the adapter transitions
     * to {@link #FAILED}.
     */
    SUSPENDED,

    /**
     * The adapter has experienced an unrecoverable failure. No further automatic
     * restart or probe attempts are made. Manual intervention is required to
     * restart the adapter (via REST API or configuration reload). This state is
     * reached when: a {@link PermanentIntegrationException} is thrown, restart
     * intensity is exceeded, or suspension cycles are exhausted.
     */
    FAILED
}
