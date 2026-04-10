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
 * Payload for {@code integration_stopped} events, produced when an adapter
 * transitions from running to stopped (Doc 05 §4.4).
 *
 * <p>Produced when the adapter is stopped by the supervisor, either due to
 * a shutdown request, health threshold breach, or configuration change. The
 * {@link #reason()} field distinguishes these cases for dashboard display
 * and log analysis.</p>
 *
 * @param integrationId   the integration instance identity; never {@code null}
 * @param integrationType the software identity (e.g., {@code "zigbee"});
 *                        never {@code null}
 * @param previousState   the health state before stopping; never {@code null}
 * @param newState        the health state after stopping (typically
 *                        {@link HealthState#FAILED} or contextual);
 *                        never {@code null}
 * @param reason          human-readable reason for stopping (e.g.,
 *                        {@code "shutdown requested"},
 *                        {@code "health threshold exceeded"});
 *                        never {@code null}
 *
 * @see IntegrationLifecycleEvent
 * @see IntegrationStarted
 */
@EventType(EventTypes.INTEGRATION_STOPPED)
public record IntegrationStopped(
        IntegrationId integrationId,
        String integrationType,
        HealthState previousState,
        HealthState newState,
        String reason
) implements IntegrationLifecycleEvent {

    public IntegrationStopped {
        Objects.requireNonNull(integrationId, "integrationId must not be null");
        Objects.requireNonNull(integrationType, "integrationType must not be null");
        Objects.requireNonNull(previousState, "previousState must not be null");
        Objects.requireNonNull(newState, "newState must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
    }
}
