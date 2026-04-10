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
 * Payload for {@code integration_resource_exceeded} events, produced when an
 * adapter exceeds a resource quota (Doc 05 §4.4).
 *
 * <p>This is a CRITICAL priority event. Resource exceedance may indicate a
 * leak, misconfiguration, or unexpected workload that threatens system stability
 * on constrained hardware (Raspberry Pi 4/5, 4 GB RAM).</p>
 *
 * <p>The {@link #resourceType()}, {@link #currentValue()}, and
 * {@link #limitValue()} fields provide structured context for dashboard
 * alerting and automated remediation decisions.</p>
 *
 * @param integrationId   the integration instance identity; never {@code null}
 * @param integrationType the software identity (e.g., {@code "zigbee"});
 *                        never {@code null}
 * @param previousState   the health state before the resource exceedance;
 *                        never {@code null}
 * @param newState        the health state after the resource exceedance;
 *                        never {@code null}
 * @param reason          human-readable description of the exceedance;
 *                        never {@code null}
 * @param resourceType    the type of resource exceeded (e.g., {@code "memory"},
 *                        {@code "cpu"}, {@code "connections"});
 *                        never {@code null}
 * @param currentValue    the current resource usage as a human-readable string
 *                        (e.g., {@code "256 MB"}, {@code "95%"});
 *                        never {@code null}
 * @param limitValue      the configured limit as a human-readable string
 *                        (e.g., {@code "128 MB"}, {@code "80%"});
 *                        never {@code null}
 *
 * @see IntegrationLifecycleEvent
 * @see HealthState
 */
@EventType(EventTypes.INTEGRATION_RESOURCE_EXCEEDED)
public record IntegrationResourceExceeded(
        IntegrationId integrationId,
        String integrationType,
        HealthState previousState,
        HealthState newState,
        String reason,
        String resourceType,
        String currentValue,
        String limitValue
) implements IntegrationLifecycleEvent {

    public IntegrationResourceExceeded {
        Objects.requireNonNull(integrationId, "integrationId must not be null");
        Objects.requireNonNull(integrationType, "integrationType must not be null");
        Objects.requireNonNull(previousState, "previousState must not be null");
        Objects.requireNonNull(newState, "newState must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(resourceType, "resourceType must not be null");
        Objects.requireNonNull(currentValue, "currentValue must not be null");
        Objects.requireNonNull(limitValue, "limitValue must not be null");
    }
}
