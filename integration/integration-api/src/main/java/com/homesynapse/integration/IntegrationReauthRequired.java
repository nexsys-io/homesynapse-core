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
 * Payload for {@code integration.reauth.required} events, produced when the
 * supervisor detects an authentication failure
 * ({@link com.homesynapse.integration.runtime.ExceptionClassification#AUTH_FAILED},
 * AMD-56) and invokes {@link IntegrationAdapter#onReauthRequired()} (AMD-58 §2.1).
 *
 * <p>A reauth demand does not itself change {@code HealthState}, so
 * {@link #previousState()} and {@link #newState()} both carry the current state
 * (non-null).</p>
 *
 * @param integrationId   the integration instance identity; never {@code null}
 * @param integrationType the software identity (e.g., {@code "zigbee"});
 *                        never {@code null}
 * @param previousState   the health state before the reauth demand;
 *                        never {@code null}
 * @param newState        the health state after the reauth demand;
 *                        never {@code null}
 * @param reason          human-readable reason for the reauth demand;
 *                        never {@code null}
 *
 * @see IntegrationLifecycleEvent
 * @see IntegrationReauthCompleted
 * @see IntegrationAdapter#onReauthRequired()
 */
@EventType(EventTypes.INTEGRATION_REAUTH_REQUIRED)
public record IntegrationReauthRequired(
        IntegrationId integrationId,
        String integrationType,
        HealthState previousState,
        HealthState newState,
        String reason
) implements IntegrationLifecycleEvent {

    public IntegrationReauthRequired {
        Objects.requireNonNull(integrationId, "integrationId must not be null");
        Objects.requireNonNull(integrationType, "integrationType must not be null");
        Objects.requireNonNull(previousState, "previousState must not be null");
        Objects.requireNonNull(newState, "newState must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
    }
}
