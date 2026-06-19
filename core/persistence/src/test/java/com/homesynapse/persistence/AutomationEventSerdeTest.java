/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homesynapse.event.AutomationCapabilityMismatchEvent;
import com.homesynapse.event.AutomationInvokedEvent;
import com.homesynapse.event.AutomationSlugRedirectEvent;
import com.homesynapse.event.AutomationTriggeredEvent;
import com.homesynapse.event.EventId;
import com.homesynapse.event.TriggerDurationCancelledEvent;
import com.homesynapse.event.TriggerDurationExpiredEvent;
import com.homesynapse.event.TriggerDurationLimitExceededEvent;
import com.homesynapse.event.TriggerDurationStartedEvent;
import com.homesynapse.event.TriggerDurationStateValidatedEvent;
import com.homesynapse.platform.identity.AutomationId;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.Ulid;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Persistence-codec round-trip tests for the AMD-92 M7.1 automation event slice (rows 1,
 * 3, 11–16, 19) — the FLATTEN residency precedent (AMD-52). Each record round-trips
 * through the {@link PersistenceObjectMapper} (typed ULID wrappers serialize as Crockford
 * strings) including nullable fields.
 */
@DisplayName("Automation event serde (AMD-92 M7.1 slice)")
class AutomationEventSerdeTest {

    private static final Ulid U1 = Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAV");
    private static final Ulid U2 = Ulid.parse("01BX5ZZKBKACTAV9WEVGEMMVRZ");
    private static final AutomationId AUTO = AutomationId.of(U1);
    private static final EntityId ENTITY = EntityId.of(U2);
    private static final EventId EVENT = EventId.of(U2);

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = PersistenceObjectMapper.create();
    }

    private <T> void roundTrip(T value, Class<T> type) throws Exception {
        byte[] bytes = mapper.writeValueAsBytes(value);
        T parsed = mapper.readValue(bytes, type);
        assertThat(parsed).isEqualTo(value);
    }

    @Test
    @DisplayName("automation_triggered (row 1) round-trips with nested map/set components")
    void triggered() throws Exception {
        roundTrip(new AutomationTriggeredEvent(U1, EVENT, List.of("t-a", "t-b"),
                Map.of("target", Set.of(ENTITY)), "hash", 3), AutomationTriggeredEvent.class);
    }

    @Test
    @DisplayName("automation_invoked (row 3) round-trips, including a null context")
    void invoked() throws Exception {
        roundTrip(new AutomationInvokedEvent("ui-button"), AutomationInvokedEvent.class);
        roundTrip(new AutomationInvokedEvent(null), AutomationInvokedEvent.class);
    }

    @Test
    @DisplayName("automation_slug_redirect (row 11) round-trips")
    void slugRedirect() throws Exception {
        roundTrip(new AutomationSlugRedirectEvent("old.slug", "new.slug", ENTITY),
                AutomationSlugRedirectEvent.class);
    }

    @Test
    @DisplayName("trigger_duration_* (rows 12–16) round-trip")
    void durationEvents() throws Exception {
        roundTrip(new TriggerDurationStartedEvent(AUTO, 0, "t1", EVENT, ENTITY, 1_800_000L),
                TriggerDurationStartedEvent.class);
        roundTrip(new TriggerDurationCancelledEvent(AUTO, 0, "t1", EVENT, "predicate_false"),
                TriggerDurationCancelledEvent.class);
        roundTrip(new TriggerDurationExpiredEvent(AUTO, 0, "t1", EVENT),
                TriggerDurationExpiredEvent.class);
        roundTrip(new TriggerDurationStateValidatedEvent(AUTO, 0, "t1", EVENT, false),
                TriggerDurationStateValidatedEvent.class);
        roundTrip(new TriggerDurationLimitExceededEvent(AUTO, 0, "t1", 256, 256),
                TriggerDurationLimitExceededEvent.class);
    }

    @Test
    @DisplayName("automation_capability_mismatch (row 19) round-trips with list components")
    void capabilityMismatch() throws Exception {
        roundTrip(new AutomationCapabilityMismatchEvent(AUTO, List.of(ENTITY),
                List.of("cap.dimming", "cap.color")), AutomationCapabilityMismatchEvent.class);
    }
}
