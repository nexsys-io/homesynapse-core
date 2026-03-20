package com.homesynapse.integration.zigbee;

import java.time.Instant;
import java.util.Objects;

/**
 * Per-device route health tracking for passive route monitoring.
 *
 * <p>Tracks consecutive and total command successes and failures per device to detect
 * degraded mesh routes. The {@link RouteStatus} transitions based on consecutive failure
 * count: 0 → {@link RouteStatus#HEALTHY}, 1–2 → {@link RouteStatus#DEGRADED},
 * 3+ → {@link RouteStatus#UNREACHABLE}. Any successful frame or command response
 * resets consecutive failures to zero and status to HEALTHY.
 *
 * <p>Doc 08 §3.15 AMD-07.
 *
 * <p>Thread-safe: immutable record.
 *
 * @param target the device's permanent IEEE EUI-64 address, never {@code null}
 * @param consecutiveFailures the number of consecutive command failures, must be non-negative
 * @param totalFailures the total number of command failures since tracking began, must be non-negative
 * @param totalSuccesses the total number of successful commands since tracking began, must be non-negative
 * @param lastSuccess the timestamp of the last successful command; {@code null} if the device has never received a successful command response
 * @param lastFailure the timestamp of the last command failure; {@code null} if the device has never experienced a command failure
 * @param status the current route health status, never {@code null}
 * @see RouteStatus
 */
public record RouteHealth(
        IEEEAddress target,
        int consecutiveFailures,
        int totalFailures,
        int totalSuccesses,
        Instant lastSuccess,
        Instant lastFailure,
        RouteStatus status) {

    /**
     * Creates a route health record with validation.
     *
     * @param target the target device IEEE address, never {@code null}
     * @param consecutiveFailures must be non-negative
     * @param totalFailures must be non-negative
     * @param totalSuccesses must be non-negative
     * @param lastSuccess {@code null} if never successful
     * @param lastFailure {@code null} if never failed
     * @param status the route status, never {@code null}
     */
    public RouteHealth {
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(status, "status must not be null");
        if (consecutiveFailures < 0) {
            throw new IllegalArgumentException(
                    "consecutiveFailures must be non-negative, got " + consecutiveFailures);
        }
        if (totalFailures < 0) {
            throw new IllegalArgumentException(
                    "totalFailures must be non-negative, got " + totalFailures);
        }
        if (totalSuccesses < 0) {
            throw new IllegalArgumentException(
                    "totalSuccesses must be non-negative, got " + totalSuccesses);
        }
    }
}
