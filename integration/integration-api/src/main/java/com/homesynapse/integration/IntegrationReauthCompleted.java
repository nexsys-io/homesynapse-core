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
 * Payload for {@code integration.reauth.completed} events, produced when an
 * adapter that returned {@link ReauthOutcome#INITIATED} finishes its asynchronous
 * re-authentication (AMD-58 §2.1).
 *
 * <p>The {@link #succeeded()} flag reports whether re-authentication succeeded.
 * Reauth completion does not itself change {@code HealthState}, so
 * {@link #previousState()} and {@link #newState()} both carry the current state
 * (non-null).</p>
 *
 * @param integrationId   the integration instance identity; never {@code null}
 * @param integrationType the software identity (e.g., {@code "zigbee"});
 *                        never {@code null}
 * @param previousState   the health state before reauth completion;
 *                        never {@code null}
 * @param newState        the health state after reauth completion;
 *                        never {@code null}
 * @param reason          human-readable reason / outcome description;
 *                        never {@code null}
 * @param succeeded       {@code true} if re-authentication succeeded
 *
 * @see IntegrationLifecycleEvent
 * @see IntegrationReauthRequired
 * @see ReauthOutcome#INITIATED
 */
@EventType(EventTypes.INTEGRATION_REAUTH_COMPLETED)
public record IntegrationReauthCompleted(
        IntegrationId integrationId,
        String integrationType,
        HealthState previousState,
        HealthState newState,
        String reason,
        boolean succeeded
) implements IntegrationLifecycleEvent {

    public IntegrationReauthCompleted {
        Objects.requireNonNull(integrationId, "integrationId must not be null");
        Objects.requireNonNull(integrationType, "integrationType must not be null");
        Objects.requireNonNull(previousState, "previousState must not be null");
        Objects.requireNonNull(newState, "newState must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
    }
}
