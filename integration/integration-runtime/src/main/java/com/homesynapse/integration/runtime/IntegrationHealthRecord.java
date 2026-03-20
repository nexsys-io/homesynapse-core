/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.runtime;

import com.homesynapse.integration.HealthState;
import com.homesynapse.platform.identity.IntegrationId;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Per-integration health state snapshot maintained by the supervisor in memory
 * (Doc 05 §4.3).
 *
 * <p>This record captures a point-in-time view of an integration's health as
 * tracked by the {@link IntegrationSupervisor}. It is not persisted —
 * health state is reconstructed on startup from the integration's current
 * operational state. The record is exposed via
 * {@link IntegrationSupervisor#health(IntegrationId)} and consumed by the
 * REST API integration health endpoints (Doc 09 §3.2, §7).</p>
 *
 * <h2>Health score calculation</h2>
 *
 * <p>The {@link #healthScore()} is a weighted composite calculated as:</p>
 * <pre>
 *   0.30 × (1 - errorRate)
 * + 0.20 × (1 - timeoutRate)
 * + 0.15 × (1 - slowCallRate)
 * + 0.20 × dataFreshnessScore
 * + 0.15 × resourceComplianceScore
 * </pre>
 *
 * <p>The {@code dataFreshnessScore} and {@code resourceComplianceScore}
 * components are computed on demand from {@link #lastHeartbeat()} and JFR
 * metrics respectively — they are not stored as fields because they are
 * time-dependent and would be stale immediately. Phase 3 computes them
 * when the health score is updated.</p>
 *
 * <h2>Sliding windows</h2>
 *
 * <p>Three {@link SlidingWindow} snapshots track error, timeout, and
 * slow-call rates independently. Each window has a configured capacity
 * (default 20 from
 * {@link com.homesynapse.integration.HealthParameters#healthWindowSize()}).
 * The rates feed into the health score formula above.</p>
 *
 * <p>This record is immutable and thread-safe.</p>
 *
 * @param integrationId       the identity of the integration instance;
 *                            never {@code null}
 * @param state               the current health state; never {@code null}
 * @param healthScore         the weighted composite health score, 0.0 (worst)
 *                            to 1.0 (best)
 * @param lastHeartbeat       timestamp of the last
 *                            {@link com.homesynapse.integration.HealthReporter#reportHeartbeat()}
 *                            call; never {@code null}
 * @param lastKeepalive       timestamp of the last successful protocol-level
 *                            keepalive; {@code null} if no keepalive has been
 *                            reported yet
 * @param stateChangedAt      timestamp of the last health state transition;
 *                            never {@code null}
 * @param consecutiveFailures count of consecutive failures since the last
 *                            stable period; resets to 0 after stable uptime
 *                            threshold; must be {@code >= 0}
 * @param suspensionCycleCount number of SUSPENDED → probe → SUSPENDED cycles;
 *                            resets on recovery to HEALTHY; must be {@code >= 0}
 * @param totalSuspendedTime  cumulative time spent in
 *                            {@link HealthState#SUSPENDED}; never {@code null}
 * @param errorWindow         error rate sliding window snapshot;
 *                            never {@code null}
 * @param timeoutWindow       timeout rate sliding window snapshot;
 *                            never {@code null}
 * @param slowCallWindow      slow-call rate sliding window snapshot;
 *                            never {@code null}
 *
 * @see IntegrationSupervisor#health(IntegrationId)
 * @see IntegrationSupervisor#allHealth()
 * @see HealthState
 * @see SlidingWindow
 * @see com.homesynapse.integration.HealthParameters
 * @see com.homesynapse.integration.HealthReporter
 */
public record IntegrationHealthRecord(
        IntegrationId integrationId,
        HealthState state,
        double healthScore,
        Instant lastHeartbeat,
        Instant lastKeepalive,
        Instant stateChangedAt,
        int consecutiveFailures,
        int suspensionCycleCount,
        Duration totalSuspendedTime,
        SlidingWindow errorWindow,
        SlidingWindow timeoutWindow,
        SlidingWindow slowCallWindow
) {

    /**
     * Validates non-null constraints and numeric range constraints.
     *
     * @throws NullPointerException     if any non-null field is {@code null}
     * @throws IllegalArgumentException if {@code healthScore} is outside
     *         [0.0, 1.0], or if {@code consecutiveFailures} or
     *         {@code suspensionCycleCount} is negative
     */
    public IntegrationHealthRecord {
        Objects.requireNonNull(integrationId, "integrationId must not be null");
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(lastHeartbeat, "lastHeartbeat must not be null");
        // lastKeepalive is nullable — null if no keepalive reported yet
        Objects.requireNonNull(stateChangedAt, "stateChangedAt must not be null");
        Objects.requireNonNull(totalSuspendedTime, "totalSuspendedTime must not be null");
        Objects.requireNonNull(errorWindow, "errorWindow must not be null");
        Objects.requireNonNull(timeoutWindow, "timeoutWindow must not be null");
        Objects.requireNonNull(slowCallWindow, "slowCallWindow must not be null");

        if (healthScore < 0.0 || healthScore > 1.0) {
            throw new IllegalArgumentException(
                    "healthScore must be between 0.0 and 1.0 inclusive: " + healthScore);
        }
        if (consecutiveFailures < 0) {
            throw new IllegalArgumentException(
                    "consecutiveFailures must be non-negative: " + consecutiveFailures);
        }
        if (suspensionCycleCount < 0) {
            throw new IllegalArgumentException(
                    "suspensionCycleCount must be non-negative: " + suspensionCycleCount);
        }
    }
}
