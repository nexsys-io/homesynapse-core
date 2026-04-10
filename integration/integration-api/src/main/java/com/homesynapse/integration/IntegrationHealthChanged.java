/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration;

import com.homesynapse.event.EventType;
import com.homesynapse.event.EventTypes;
import com.homesynapse.platform.identity.IntegrationId;

import java.util.Objects;

/**
 * Payload for {@code integration_health_changed} events, produced when the
 * supervisor transitions an adapter between health states (Doc 05 §4.4).
 *
 * <p>Carries the additional {@link #healthScore()} field — the weighted health
 * score at the time of the transition — which provides quantitative context for
 * dashboard display and alerting. Health score ranges from 0.0 (completely
 * unhealthy) to 1.0 (fully healthy).</p>
 *
 * <p>The event priority depends on the transition: NORMAL priority for
 * transitions to {@link HealthState#DEGRADED}, CRITICAL priority for
 * transitions to {@link HealthState#SUSPENDED} or {@link HealthState#FAILED}.</p>
 *
 * @param integrationId   the integration instance identity; never {@code null}
 * @param integrationType the software identity (e.g., {@code "zigbee"});
 *                        never {@code null}
 * @param previousState   the health state before the transition;
 *                        never {@code null}
 * @param newState        the health state after the transition;
 *                        never {@code null}
 * @param reason          human-readable reason for the transition (e.g.,
 *                        {@code "health score below threshold (0.35)"});
 *                        never {@code null}
 * @param healthScore     the current weighted health score at time of
 *                        transition, ranging from 0.0 to 1.0
 *
 * @see IntegrationLifecycleEvent
 * @see HealthState
 * @see HealthReporter
 */
@EventType(EventTypes.INTEGRATION_HEALTH_CHANGED)
public record IntegrationHealthChanged(
        IntegrationId integrationId,
        String integrationType,
        HealthState previousState,
        HealthState newState,
        String reason,
        double healthScore
) implements IntegrationLifecycleEvent {

    public IntegrationHealthChanged {
        Objects.requireNonNull(integrationId, "integrationId must not be null");
        Objects.requireNonNull(integrationType, "integrationType must not be null");
        Objects.requireNonNull(previousState, "previousState must not be null");
        Objects.requireNonNull(newState, "newState must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
    }
}
