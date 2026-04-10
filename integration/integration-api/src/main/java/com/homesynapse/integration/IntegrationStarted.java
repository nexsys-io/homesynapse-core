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
 * Payload for {@code integration_started} events, produced when an adapter
 * transitions to the running state (Doc 05 §4.4).
 *
 * <p>This is the first lifecycle event for an integration instance. The
 * {@link #previousState()} is always {@code null} because no prior health
 * state exists at initial startup. The {@link #newState()} is always
 * {@link HealthState#HEALTHY} — the adapter starts in healthy state and the
 * health state machine takes over from there.</p>
 *
 * @param integrationId   the integration instance identity; never {@code null}
 * @param integrationType the software identity (e.g., {@code "zigbee"});
 *                        never {@code null}
 * @param newState        the initial health state, always
 *                        {@link HealthState#HEALTHY}; never {@code null}
 * @param reason          human-readable reason for startup (e.g.,
 *                        {@code "initial startup"}, {@code "configuration reload"});
 *                        never {@code null}
 *
 * @see IntegrationLifecycleEvent
 * @see IntegrationStopped
 */
@EventType(EventTypes.INTEGRATION_STARTED)
public record IntegrationStarted(
        IntegrationId integrationId,
        String integrationType,
        HealthState newState,
        String reason
) implements IntegrationLifecycleEvent {

    public IntegrationStarted {
        Objects.requireNonNull(integrationId, "integrationId must not be null");
        Objects.requireNonNull(integrationType, "integrationType must not be null");
        Objects.requireNonNull(newState, "newState must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
    }

    /**
     * Always returns {@code null} — no previous state exists at initial startup.
     *
     * @return {@code null}
     */
    @Override
    public HealthState previousState() {
        return null;
    }
}
