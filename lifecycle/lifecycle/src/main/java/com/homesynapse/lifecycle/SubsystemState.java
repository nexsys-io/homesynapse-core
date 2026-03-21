/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.lifecycle;

import java.time.Duration;
import java.util.Objects;

import com.homesynapse.observability.HealthStatus;

/**
 * Individual subsystem's state snapshot within the overall system lifecycle.
 *
 * <p>Tracks the subsystem's current phase assignment, initialization progress,
 * health status (from the observability module), and any initialization error
 * that occurred. Instances are captured by {@link SystemLifecycleManager} and
 * included in {@link SystemHealthSnapshot} for health endpoint consumption.</p>
 *
 * @param subsystemName human-readable identifier for the subsystem
 *        (e.g., "event-bus", "state-store", "persistence-layer")
 * @param phase the {@link LifecyclePhase} this subsystem is assigned to
 * @param status current status of the subsystem within its lifecycle
 * @param healthState the subsystem's health status as reported by its
 *        {@code HealthContributor} ({@code HEALTHY}, {@code DEGRADED}, or
 *        {@code UNHEALTHY}), or {@code null} during early initialization
 *        before the health contributor is available (prior to Phase 4)
 * @param initializationDuration elapsed time from when initialization started
 *        to when it completed (success or failure), or {@code null} if the
 *        subsystem has not yet started initialization
 * @param error diagnostic message if status is {@code FAILED}; {@code null}
 *        otherwise. Examples: "SQLite database integrity check failed",
 *        "Persistence Layer initialization timed out after 60 seconds",
 *        "Configuration schema validation found 3 fatal errors"
 *
 * @see SystemLifecycleManager#subsystemStates()
 * @see SystemHealthSnapshot
 */
public record SubsystemState(
        String subsystemName,
        LifecyclePhase phase,
        SubsystemStatus status,
        HealthStatus healthState,
        Duration initializationDuration,
        String error
) {

    /**
     * Creates a validated subsystem state snapshot.
     *
     * @throws NullPointerException if {@code subsystemName}, {@code phase},
     *         or {@code status} is {@code null}
     * @throws IllegalArgumentException if {@code status} is {@code FAILED}
     *         and {@code error} is {@code null}, or if {@code status} is
     *         {@code RUNNING} or {@code STOPPED} and {@code error} is non-null
     */
    public SubsystemState {
        Objects.requireNonNull(subsystemName, "subsystemName must not be null");
        Objects.requireNonNull(phase, "phase must not be null");
        Objects.requireNonNull(status, "status must not be null");
        // healthState is explicitly nullable (null before Phase 4 health contributor setup)
        // initializationDuration is nullable (null before initialization starts)
        if (status == SubsystemStatus.FAILED && error == null) {
            throw new IllegalArgumentException(
                    "error must be non-null when status is FAILED for subsystem: "
                            + subsystemName);
        }
        if ((status == SubsystemStatus.RUNNING || status == SubsystemStatus.STOPPED)
                && error != null) {
            throw new IllegalArgumentException(
                    "error must be null when status is " + status
                            + " for subsystem: " + subsystemName);
        }
    }
}
