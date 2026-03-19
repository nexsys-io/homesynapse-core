/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration;

import java.time.Duration;
import java.util.Objects;

/**
 * Per-integration health thresholds and restart limits that configure the
 * supervisor's health state machine for a specific adapter (Doc 05 §4.2).
 *
 * <p>Each adapter declares its health parameters in
 * {@link IntegrationDescriptor#healthParameters()}. The supervisor uses these
 * values to determine when to transition the adapter between {@link HealthState}
 * states, how aggressively to probe during suspension, and when to give up and
 * transition to {@link HealthState#FAILED}.</p>
 *
 * <p>Most adapters should use {@link #defaults()}, which provides sensible values
 * for network polling integrations: 120-second heartbeat timeout with a 30-second
 * watchdog ping provides 4 missed pings before stale detection, and 3/60s restart
 * intensity prevents restart storms from serial port flapping.</p>
 *
 * <p>This record is immutable and thread-safe.</p>
 *
 * @param heartbeatTimeout     maximum silence from the adapter before the supervisor
 *                             flags it as unresponsive; never {@code null}
 * @param healthWindowSize     number of samples in the sliding window for
 *                             error/timeout rate calculations; must be positive
 * @param maxDegradedDuration  maximum time the adapter may remain in
 *                             {@link HealthState#DEGRADED} before transitioning
 *                             to {@link HealthState#SUSPENDED}; never {@code null}
 * @param maxSuspendedDuration maximum total time the adapter may spend in
 *                             {@link HealthState#SUSPENDED} across all suspension
 *                             cycles before transitioning to {@link HealthState#FAILED};
 *                             never {@code null}
 * @param maxSuspensionCycles  maximum number of SUSPENDED → probe → SUSPENDED cycles
 *                             before the adapter transitions to {@link HealthState#FAILED};
 *                             must be positive
 * @param maxRestarts          restart intensity limit — maximum restarts within
 *                             {@code restartWindow} before transitioning to
 *                             {@link HealthState#FAILED}; must be positive
 * @param restartWindow        time window for restart intensity tracking;
 *                             never {@code null}
 * @param probeInitialDelay    delay before the first recovery probe in
 *                             {@link HealthState#SUSPENDED}; never {@code null}
 * @param probeMaxDelay        maximum delay between recovery probes (exponential
 *                             backoff cap); never {@code null}
 * @param probeCount           number of probes per suspension cycle; must be positive
 * @param probeSuccessThreshold number of successful probes required to transition
 *                             back to {@link HealthState#HEALTHY}; must be positive
 *                             and not greater than {@code probeCount}
 *
 * @see HealthState
 * @see IntegrationDescriptor
 * @see HealthReporter
 */
public record HealthParameters(
        Duration heartbeatTimeout,
        int healthWindowSize,
        Duration maxDegradedDuration,
        Duration maxSuspendedDuration,
        int maxSuspensionCycles,
        int maxRestarts,
        Duration restartWindow,
        Duration probeInitialDelay,
        Duration probeMaxDelay,
        int probeCount,
        int probeSuccessThreshold
) {

    /**
     * Validates that all duration fields are non-null and numeric fields are positive.
     */
    public HealthParameters {
        Objects.requireNonNull(heartbeatTimeout, "heartbeatTimeout must not be null");
        Objects.requireNonNull(maxDegradedDuration, "maxDegradedDuration must not be null");
        Objects.requireNonNull(maxSuspendedDuration, "maxSuspendedDuration must not be null");
        Objects.requireNonNull(restartWindow, "restartWindow must not be null");
        Objects.requireNonNull(probeInitialDelay, "probeInitialDelay must not be null");
        Objects.requireNonNull(probeMaxDelay, "probeMaxDelay must not be null");
        if (healthWindowSize <= 0) {
            throw new IllegalArgumentException("healthWindowSize must be positive: " + healthWindowSize);
        }
        if (maxSuspensionCycles <= 0) {
            throw new IllegalArgumentException("maxSuspensionCycles must be positive: " + maxSuspensionCycles);
        }
        if (maxRestarts <= 0) {
            throw new IllegalArgumentException("maxRestarts must be positive: " + maxRestarts);
        }
        if (probeCount <= 0) {
            throw new IllegalArgumentException("probeCount must be positive: " + probeCount);
        }
        if (probeSuccessThreshold <= 0 || probeSuccessThreshold > probeCount) {
            throw new IllegalArgumentException(
                    "probeSuccessThreshold must be between 1 and probeCount (%d): %d"
                            .formatted(probeCount, probeSuccessThreshold));
        }
    }

    /**
     * Returns a {@code HealthParameters} instance with default values suitable for
     * network polling integrations.
     *
     * <p>Default values:</p>
     * <ul>
     *   <li>{@code heartbeatTimeout}: 120 seconds</li>
     *   <li>{@code healthWindowSize}: 20 samples</li>
     *   <li>{@code maxDegradedDuration}: 5 minutes</li>
     *   <li>{@code maxSuspendedDuration}: 1 hour</li>
     *   <li>{@code maxSuspensionCycles}: 5</li>
     *   <li>{@code maxRestarts}: 3</li>
     *   <li>{@code restartWindow}: 60 seconds</li>
     *   <li>{@code probeInitialDelay}: 30 seconds</li>
     *   <li>{@code probeMaxDelay}: 5 minutes</li>
     *   <li>{@code probeCount}: 3</li>
     *   <li>{@code probeSuccessThreshold}: 2</li>
     * </ul>
     *
     * @return a new {@code HealthParameters} with all default values, never {@code null}
     */
    public static HealthParameters defaults() {
        return new HealthParameters(
                Duration.ofSeconds(120),    // heartbeatTimeout
                20,                         // healthWindowSize
                Duration.ofMinutes(5),      // maxDegradedDuration
                Duration.ofHours(1),        // maxSuspendedDuration
                5,                          // maxSuspensionCycles
                3,                          // maxRestarts
                Duration.ofSeconds(60),     // restartWindow
                Duration.ofSeconds(30),     // probeInitialDelay
                Duration.ofMinutes(5),      // probeMaxDelay
                3,                          // probeCount
                2                           // probeSuccessThreshold
        );
    }
}
