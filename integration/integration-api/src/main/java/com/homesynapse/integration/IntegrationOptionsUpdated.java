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
 * Payload for {@code integration.options.updated} events, produced when the
 * supervisor delivers a runtime-tunable options change to an adapter via
 * {@link IntegrationAdapter#onOptionsUpdated(com.homesynapse.config.ConfigChangeSet)}
 * (AMD-58 §2.1).
 *
 * <p>Options are the additive/minor subset of configuration — polling intervals,
 * rate limits, log verbosity. The {@link #outcome()} records how the adapter
 * handled the change. An options update does not change {@code HealthState}, so
 * {@link #previousState()} and {@link #newState()} both carry the current state
 * (non-null).</p>
 *
 * @param integrationId   the integration instance identity; never {@code null}
 * @param integrationType the software identity (e.g., {@code "zigbee"});
 *                        never {@code null}
 * @param previousState   the health state before the update; never {@code null}
 * @param newState        the health state after the update; never {@code null}
 * @param reason          human-readable reason for the update; never {@code null}
 * @param outcome         how the adapter handled the options change;
 *                        never {@code null}
 *
 * @see IntegrationLifecycleEvent
 * @see ConfigUpdateOutcome
 * @see IntegrationAdapter#onOptionsUpdated(com.homesynapse.config.ConfigChangeSet)
 */
@EventType(EventTypes.INTEGRATION_OPTIONS_UPDATED)
public record IntegrationOptionsUpdated(
        IntegrationId integrationId,
        String integrationType,
        HealthState previousState,
        HealthState newState,
        String reason,
        ConfigUpdateOutcome outcome
) implements IntegrationLifecycleEvent {

    public IntegrationOptionsUpdated {
        Objects.requireNonNull(integrationId, "integrationId must not be null");
        Objects.requireNonNull(integrationType, "integrationType must not be null");
        Objects.requireNonNull(previousState, "previousState must not be null");
        Objects.requireNonNull(newState, "newState must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
    }
}
