/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration;

import java.time.Duration;
import java.util.Objects;

/**
 * Declares the transient-failure retry backoff schedule for an integration, on
 * {@link IntegrationDescriptor#backoffParameters()} (AMD-62).
 *
 * <p>The retry schedule is a pure function of these parameters and the attempt
 * count — deterministic, with no hidden state (AMD-62-INV-01). The M9 supervisor
 * consumes these parameters in the transient-retry path.</p>
 *
 * <p>This is distinct from recovery probing
 * ({@link HealthParameters#probeInitialDelay()},
 * {@link HealthParameters#probeMaxDelay()}): retry backoff governs reconnection
 * after a transient failure, probing governs recovery checks during suspension.
 * Neither reuses the other's parameters (AMD-62-INV-02).</p>
 *
 * <p>This record is immutable and thread-safe.</p>
 *
 * @param initialDelay the delay before the first retry; must be positive
 * @param multiplier   the exponential growth factor applied per attempt; must be
 *                     {@code >= 1.0}
 * @param maxDelay     the cap on the retry delay; must be {@code >=}
 *                     {@code initialDelay}
 *
 * @see IntegrationDescriptor#backoffParameters()
 * @see HealthParameters
 */
public record BackoffParameters(
        Duration initialDelay,
        double multiplier,
        Duration maxDelay
) {

    /**
     * Validates that the delays are non-null, the initial delay is positive, the
     * multiplier is at least 1.0, and the maximum delay is not less than the
     * initial delay.
     */
    public BackoffParameters {
        Objects.requireNonNull(initialDelay, "initialDelay must not be null");
        Objects.requireNonNull(maxDelay, "maxDelay must not be null");
        if (initialDelay.isZero() || initialDelay.isNegative()) {
            throw new IllegalArgumentException(
                    "initialDelay must be positive: " + initialDelay);
        }
        if (multiplier < 1.0) {
            throw new IllegalArgumentException(
                    "multiplier must be >= 1.0: " + multiplier);
        }
        if (maxDelay.compareTo(initialDelay) < 0) {
            throw new IllegalArgumentException(
                    "maxDelay must be >= initialDelay (%s): %s"
                            .formatted(initialDelay, maxDelay));
        }
    }

    /**
     * Returns the default backoff schedule, reproducing the empirically-derived
     * Home Assistant schedule exactly: 5, 10, 20, 40, 80, 80, &hellip; seconds
     * (initial delay 5s, multiplier 2.0, capped at 80s).
     *
     * <p>No jitter field — deterministic schedules are testable; jitter, if the
     * supervisor wants it, is supervisor policy, not adapter contract.</p>
     *
     * @return the default backoff parameters, never {@code null}
     */
    public static BackoffParameters defaults() {
        return new BackoffParameters(Duration.ofSeconds(5), 2.0, Duration.ofSeconds(80));
    }
}
