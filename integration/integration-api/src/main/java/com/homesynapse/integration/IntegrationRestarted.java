/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration;

import com.homesynapse.platform.identity.IntegrationId;

import java.util.Objects;

/**
 * Payload for {@code integration_restarted} events, produced when the supervisor
 * successfully restarts an adapter after a failure (Doc 05 §4.4).
 *
 * <p>Carries the additional {@link #restartCount()} field — how many restarts
 * have occurred within the current restart intensity window
 * ({@link HealthParameters#restartWindow()}). If the restart count exceeds
 * {@link HealthParameters#maxRestarts()}, the supervisor transitions to
 * {@link HealthState#FAILED} instead of attempting another restart.</p>
 *
 * @param integrationId   the integration instance identity; never {@code null}
 * @param integrationType the software identity (e.g., {@code "zigbee"});
 *                        never {@code null}
 * @param previousState   the health state before restart;
 *                        never {@code null}
 * @param newState        the health state after successful restart (typically
 *                        {@link HealthState#HEALTHY}); never {@code null}
 * @param reason          human-readable reason for the restart (e.g.,
 *                        {@code "adapter threw IOException during run()"});
 *                        never {@code null}
 * @param restartCount    number of restarts within the current intensity
 *                        window; must be non-negative
 *
 * @see IntegrationLifecycleEvent
 * @see HealthParameters#maxRestarts()
 * @see HealthParameters#restartWindow()
 */
public record IntegrationRestarted(
        IntegrationId integrationId,
        String integrationType,
        HealthState previousState,
        HealthState newState,
        String reason,
        int restartCount
) implements IntegrationLifecycleEvent {

    public IntegrationRestarted {
        Objects.requireNonNull(integrationId, "integrationId must not be null");
        Objects.requireNonNull(integrationType, "integrationType must not be null");
        Objects.requireNonNull(previousState, "previousState must not be null");
        Objects.requireNonNull(newState, "newState must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
    }
}
