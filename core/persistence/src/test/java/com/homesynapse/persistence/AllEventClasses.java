/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import com.homesynapse.event.DomainEvent;
import com.homesynapse.event.EventTypes;
import com.homesynapse.integration.IntegrationEvents;

import java.util.List;
import java.util.stream.Stream;

/**
 * Test-only event class roster for the persistence serialization tests that
 * register classes with the {@link EventTypeRegistry}.
 *
 * <p>The lists are aliased to the canonical per-module manifests:
 * {@link EventTypes#CORE_PRODUCTION_EVENT_CLASSES} contributes the 37 core records
 * (24 prior + 8 from the M7.1 run-initiation slice + 5 from the M7.2 run-lifecycle slice,
 * AMD-92), {@link IntegrationEvents#LIFECYCLE_EVENT_CLASSES} contributes the 10 integration
 * lifecycle records (5 original + 5 added by AMD-58), and
 * {@link IntegrationEvents#CAPABILITY_EVENT_CLASSES} contributes the 2 capability
 * records (AMD-59). All manifests are public production API in their respective
 * modules; this class exposes them under the shorter field names that the persistence
 * test suite already references and combines them via {@link Stream}.
 *
 * <p>The aggregation pattern here mirrors what the composition root performs at
 * startup to construct the production {@code EventTypeRegistry}.
 */
final class AllEventClasses {

    private AllEventClasses() {
        // Utility class — non-instantiable
    }

    /** 41 core domain event records from event-model, all carrying {@code @EventType}. */
    static final List<Class<? extends DomainEvent>> CORE_EVENTS =
            EventTypes.CORE_PRODUCTION_EVENT_CLASSES;

    /** 10 integration lifecycle event records from integration-api. */
    static final List<Class<? extends DomainEvent>> INTEGRATION_EVENTS =
            IntegrationEvents.LIFECYCLE_EVENT_CLASSES;

    /** 2 capability event records from integration-api (AMD-59). */
    static final List<Class<? extends DomainEvent>> CAPABILITY_EVENTS =
            IntegrationEvents.CAPABILITY_EVENT_CLASSES;

    /** All 53 registered event record classes — core + integration lifecycle + capability. */
    static final List<Class<? extends DomainEvent>> ALL_EVENTS =
            Stream.of(CORE_EVENTS, INTEGRATION_EVENTS, CAPABILITY_EVENTS)
                    .flatMap(List::stream)
                    .toList();
}
