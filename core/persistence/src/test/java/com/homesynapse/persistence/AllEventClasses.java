/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import com.homesynapse.event.AutomationCompletedEvent;
import com.homesynapse.event.AutomationTriggeredEvent;
import com.homesynapse.event.AvailabilityChangedEvent;
import com.homesynapse.event.CommandConfirmationTimedOutEvent;
import com.homesynapse.event.CommandDispatchedEvent;
import com.homesynapse.event.CommandIssuedEvent;
import com.homesynapse.event.CommandResultEvent;
import com.homesynapse.event.ConfigChangedEvent;
import com.homesynapse.event.ConfigErrorEvent;
import com.homesynapse.event.DeviceAdoptedEvent;
import com.homesynapse.event.DeviceDiscoveredEvent;
import com.homesynapse.event.DeviceRemovedEvent;
import com.homesynapse.event.DomainEvent;
import com.homesynapse.event.PresenceChangedEvent;
import com.homesynapse.event.PresenceSignalEvent;
import com.homesynapse.event.StateChangedEvent;
import com.homesynapse.event.StateConfirmedEvent;
import com.homesynapse.event.StateReportRejectedEvent;
import com.homesynapse.event.StateReportedEvent;
import com.homesynapse.event.StoragePressureChangedEvent;
import com.homesynapse.event.SystemStartedEvent;
import com.homesynapse.event.SystemStoppedEvent;
import com.homesynapse.event.TelemetrySummaryEvent;
import com.homesynapse.integration.IntegrationHealthChanged;
import com.homesynapse.integration.IntegrationResourceExceeded;
import com.homesynapse.integration.IntegrationRestarted;
import com.homesynapse.integration.IntegrationStarted;
import com.homesynapse.integration.IntegrationStopped;

import java.util.List;

/**
 * Test-only mirror of the authoritative event class lists that the M2.4
 * persistence serialization tests register with the {@link EventTypeRegistry}.
 *
 * <p>The 22 core event records mirror {@code EXPECTED_EVENT_RECORDS} in
 * {@code com.homesynapse.event.EventTypeAnnotationTest} (event-model test
 * source set). The 5 integration event records mirror
 * {@code EXPECTED_SUBTYPES} in
 * {@code com.homesynapse.integration.IntegrationEventTypeAnnotationTest}
 * (integration-api test source set). Both are private lists in their home
 * modules; this file is a deliberate copy so that the persistence tests have
 * a single local reference. If either upstream list changes, the
 * corresponding upstream annotation test fails; updating this file is a
 * follow-up step.</p>
 */
final class AllEventClasses {

    private AllEventClasses() {
        // Utility class — non-instantiable
    }

    /** 22 core domain event records from event-model, all carrying {@code @EventType}. */
    static final List<Class<? extends DomainEvent>> CORE_EVENTS = List.of(
            CommandIssuedEvent.class,
            CommandDispatchedEvent.class,
            CommandResultEvent.class,
            CommandConfirmationTimedOutEvent.class,
            StateReportedEvent.class,
            StateReportRejectedEvent.class,
            StateChangedEvent.class,
            StateConfirmedEvent.class,
            DeviceDiscoveredEvent.class,
            DeviceAdoptedEvent.class,
            DeviceRemovedEvent.class,
            AvailabilityChangedEvent.class,
            AutomationTriggeredEvent.class,
            AutomationCompletedEvent.class,
            PresenceSignalEvent.class,
            PresenceChangedEvent.class,
            SystemStartedEvent.class,
            SystemStoppedEvent.class,
            StoragePressureChangedEvent.class,
            ConfigChangedEvent.class,
            ConfigErrorEvent.class,
            TelemetrySummaryEvent.class);

    /** 5 integration lifecycle event records from integration-api. */
    static final List<Class<? extends DomainEvent>> INTEGRATION_EVENTS = List.of(
            IntegrationStarted.class,
            IntegrationStopped.class,
            IntegrationHealthChanged.class,
            IntegrationRestarted.class,
            IntegrationResourceExceeded.class);

    /** All 27 registered event record classes — core + integration. */
    static final List<Class<? extends DomainEvent>> ALL_EVENTS;

    static {
        var combined = new java.util.ArrayList<Class<? extends DomainEvent>>(
                CORE_EVENTS.size() + INTEGRATION_EVENTS.size());
        combined.addAll(CORE_EVENTS);
        combined.addAll(INTEGRATION_EVENTS);
        ALL_EVENTS = List.copyOf(combined);
    }
}
