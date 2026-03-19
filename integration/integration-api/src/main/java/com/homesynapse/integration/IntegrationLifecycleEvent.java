/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration;

import com.homesynapse.event.DomainEvent;
import com.homesynapse.platform.identity.IntegrationId;

/**
 * Sealed root of the integration lifecycle event payload hierarchy
 * (Doc 05 §4.4, §8.2).
 *
 * <p>The integration runtime produces these events via
 * {@link com.homesynapse.event.EventPublisher} when an adapter's lifecycle
 * state changes. All lifecycle events carry
 * {@link com.homesynapse.event.EventEnvelope} with
 * {@code EventOrigin.SYSTEM} — they are system-initiated, not
 * integration-initiated.</p>
 *
 * <p>Five permitted subtypes correspond to the five lifecycle event types:</p>
 * <ul>
 *   <li>{@link IntegrationStarted} — {@code integration_started}</li>
 *   <li>{@link IntegrationStopped} — {@code integration_stopped}</li>
 *   <li>{@link IntegrationHealthChanged} — {@code integration_health_changed}</li>
 *   <li>{@link IntegrationRestarted} — {@code integration_restarted}</li>
 *   <li>{@link IntegrationResourceExceeded} — {@code integration_resource_exceeded}</li>
 * </ul>
 *
 * <p>Every subtype carries the common fields: {@code integrationId},
 * {@code integrationType}, {@code previousState} (nullable for
 * {@link IntegrationStarted}), {@code newState}, and {@code reason}
 * (human-readable). The {@code reason} field uses Register C voice
 * (direct, neutral) because it surfaces in dashboards and logs
 * (INV-HO-04).</p>
 *
 * @see IntegrationStarted
 * @see IntegrationStopped
 * @see IntegrationHealthChanged
 * @see IntegrationRestarted
 * @see IntegrationResourceExceeded
 * @see com.homesynapse.event.EventPublisher
 */
public sealed interface IntegrationLifecycleEvent extends DomainEvent
        permits IntegrationStarted,
                IntegrationStopped,
                IntegrationHealthChanged,
                IntegrationRestarted,
                IntegrationResourceExceeded {

    /**
     * Returns the integration instance identity.
     *
     * @return the integration ID, never {@code null}
     */
    IntegrationId integrationId();

    /**
     * Returns the software identity of the integration (e.g., {@code "zigbee"}).
     *
     * @return the integration type string, never {@code null}
     */
    String integrationType();

    /**
     * Returns the health state before this lifecycle transition, or {@code null}
     * for {@link IntegrationStarted} events where no previous state exists.
     *
     * @return the previous health state, or {@code null} for initial start events
     */
    HealthState previousState();

    /**
     * Returns the health state after this lifecycle transition.
     *
     * @return the new health state, never {@code null}
     */
    HealthState newState();

    /**
     * Returns a human-readable reason for this lifecycle transition.
     *
     * <p>Uses Register C voice: direct, neutral, no self-reference. Example:
     * {@code "health score below threshold (0.35)"}, {@code "shutdown requested"},
     * {@code "restart intensity exceeded (4 restarts in 60 seconds)"}.</p>
     *
     * @return the reason string, never {@code null}
     */
    String reason();
}
