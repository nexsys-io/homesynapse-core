/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

import static org.assertj.core.api.Assertions.assertThat;

import com.homesynapse.device.Capability;
import com.homesynapse.device.CapabilityInstance;
import com.homesynapse.device.Entity;
import com.homesynapse.device.EntityType;
import com.homesynapse.device.InMemoryEntityRegistry;
import com.homesynapse.device.StandardCapabilities;
import com.homesynapse.event.AvailabilityChangedEvent;
import com.homesynapse.event.CausalContext;
import com.homesynapse.event.DomainEvent;
import com.homesynapse.event.EventCategory;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventId;
import com.homesynapse.event.EventOrigin;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.StateChangedEvent;
import com.homesynapse.event.StateReportedEvent;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.event.bus.SubscriberMode;
import com.homesynapse.event.test.InMemoryEventStore;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.Ulid;
import com.homesynapse.state.test.InMemoryViewCheckpointStore;
import com.homesynapse.value.StringValue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * IR-61 — the projection's {@code state_reported} branch resolves {@code staleAfter} through
 * the {@link StalenessThresholdResolver} (Doc 03 §3.8): {@code staleAfter = stamp + threshold}
 * on the EVENT-TIME stamp (AMD-53-INV-02), {@code stale} cleared; every other branch carries
 * both forward. Built in {@link InMemoryStateProjectionTest}'s shape through the resolver
 * overload of {@link StateProjection#create}. The projection clock is fixed at an instant
 * distinct from every event-time and every ingest-time below, so a {@code staleAfter} sourced
 * from the clock or from the ingest time cannot pass.
 */
@DisplayName("StateProjection — staleAfter from the threshold chain (IR-61, Doc 03 §3.8)")
final class StateProjectionStalenessTest {

    /** The projection clock — never an event-time below. */
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    /** G4-2's last report before its silence (2026-09-26, the v81 audits). */
    private static final Instant T = Instant.parse("2026-09-26T22:34:31Z");
    private static final Instant T2 = T.plusSeconds(1500);
    /** power_meter's declared interval: 2 × ActivePower's configured 600-s maximum. */
    private static final Duration POWER_METER = Duration.ofSeconds(1200);
    private static final StalenessThresholdResolver POWER_METER_THRESHOLD =
            entityId -> Optional.of(POWER_METER);

    private final EntityId entityId = new EntityId(new Ulid(0x61L, 0x61L));
    private final SubjectRef subject = SubjectRef.entity(entityId);
    private InMemoryStateStore stateStore;
    private long position;

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    StateProjectionStalenessTest() {
    }

    @BeforeEach
    void setUp() {
        stateStore = new InMemoryStateStore();
        position = 0L;
    }

    @Test
    @DisplayName("T2: a state_reported at event-time T → staleAfter = T + 1200 s, stale false, "
            + "lastReported T; the 12-parameter create resolves none() → null, as before")
    void stateReportedStampsStaleAfterFromTheThreshold() {
        StateProjection projection = projection(stateStore, POWER_METER_THRESHOLD);

        projection.onEvent(reported(T));

        EntityState state = stateStore.get(entityId).orElseThrow();
        assertThat(state.staleAfter())
                .as("the event-time stamp + the threshold — never the clock, never ingestTime")
                .isEqualTo(Instant.parse("2026-09-26T22:54:31Z"));
        assertThat(state.stale()).isFalse();
        assertThat(state.lastReported()).isEqualTo(T);

        InMemoryStateStore legacyStore = new InMemoryStateStore();
        legacyProjection(legacyStore).onEvent(reported(T));
        EntityState legacy = legacyStore.get(entityId).orElseThrow();
        assertThat(legacy.staleAfter())
                .as("every existing caller keeps the 12-parameter create → none() → null")
                .isNull();
        assertThat(legacy.stale()).isFalse();
    }

    @Test
    @DisplayName("T2b: a report replayed before its entity's registration resolves null; the "
            + "registration alone moves nothing; the next report resolves T2 + 1200 s")
    void aReportBeforeRegistrationResolvesNullUntilTheNext() {
        InMemoryEntityRegistry registry = new InMemoryEntityRegistry();
        StateProjection projection = projection(stateStore, new RegistryStalenessResolver(
                registry, StandardCapabilities.all(), Map.of(), Optional.empty()));

        projection.onEvent(reported(T));
        assertThat(stateStore.get(entityId).orElseThrow().staleAfter())
                .as("a registry miss with no global default → null (DP-3's replay order)")
                .isNull();

        registry.createEntity(new Entity(entityId, "gen4-plug", EntityType.PLUG, "Plug", null,
                1, null, true, List.of(), List.of(instanceOf(StandardCapabilities.onOff()),
                        instanceOf(StandardCapabilities.powerMeter()),
                        instanceOf(StandardCapabilities.energyMeter())), T));
        assertThat(stateStore.get(entityId).orElseThrow().staleAfter())
                .as("staleAfter recomputes on a report, never on a registration")
                .isNull();

        projection.onEvent(reported(T2));
        assertThat(stateStore.get(entityId).orElseThrow().staleAfter())
                .isEqualTo(T2.plus(POWER_METER));
    }

    @Test
    @DisplayName("T3: a report clears a stored stale and recomputes staleAfter; a state_changed "
            + "or availability_changed carries staleAfter and stale untouched")
    void aReportClearsStale() {
        StateProjection projection = projection(stateStore, POWER_METER_THRESHOLD);
        Instant oldStaleAfter = T.minusSeconds(60);
        stateStore.put(entityId, new EntityState(entityId, Map.of(), Availability.AVAILABLE, 5L,
                T.minusSeconds(1300), T.minusSeconds(1260), T.minusSeconds(1260),
                oldStaleAfter, true));

        projection.onEvent(changed(T, "81.0"));
        EntityState afterChange = stateStore.get(entityId).orElseThrow();
        assertThat(afterChange.attributes()).containsKey("power_w");
        assertThat(afterChange.staleAfter()).isEqualTo(oldStaleAfter);
        assertThat(afterChange.stale()).isTrue();

        projection.onEvent(availability(T.plusSeconds(1)));
        EntityState afterAvailability = stateStore.get(entityId).orElseThrow();
        assertThat(afterAvailability.availability()).isEqualTo(Availability.UNAVAILABLE);
        assertThat(afterAvailability.staleAfter()).isEqualTo(oldStaleAfter);
        assertThat(afterAvailability.stale()).isTrue();

        projection.onEvent(reported(T2));
        EntityState afterReport = stateStore.get(entityId).orElseThrow();
        assertThat(afterReport.stale()).as("Doc 03 §3.8: a report resets stale to false").isFalse();
        assertThat(afterReport.staleAfter()).isEqualTo(T2.plus(POWER_METER));

        projection.onEvent(changed(T2.plusSeconds(1), "82.0"));
        assertThat(stateStore.get(entityId).orElseThrow().staleAfter())
                .as("a later state_changed carries the recomputed staleAfter")
                .isEqualTo(T2.plus(POWER_METER));
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    /** The resolver overload of {@code create}: the resolver LAST (IR-61). */
    private static StateProjection projection(InMemoryStateStore store,
            StalenessThresholdResolver thresholds) {
        InMemoryEventStore events = new InMemoryEventStore(CLOCK);
        InMemoryViewCheckpointStore checkpoints = new InMemoryViewCheckpointStore(CLOCK);
        StateProjection projection = StateProjection.create(
                new ProjectionId("state_projection"), 1, checkpoints,
                StateCheckpointSource.stub(), AtomicCheckpointSink.viewOnly(checkpoints),
                store, DerivationRule.production(), events,
                new InMemoryProjectionAdvancer(events), FixedCheckpointPolicy.HOME_DEFAULT,
                CLOCK, DerivedPublishGate.unbounded(), thresholds);
        projection.setMode(SubscriberMode.LIVE);
        return projection;
    }

    /** The 12-parameter {@code create} every pre-IR-61 caller keeps. */
    private static StateProjection legacyProjection(InMemoryStateStore store) {
        InMemoryEventStore events = new InMemoryEventStore(CLOCK);
        InMemoryViewCheckpointStore checkpoints = new InMemoryViewCheckpointStore(CLOCK);
        StateProjection projection = StateProjection.create(
                new ProjectionId("state_projection"), 1, checkpoints,
                StateCheckpointSource.stub(), AtomicCheckpointSink.viewOnly(checkpoints),
                store, DerivationRule.production(), events,
                new InMemoryProjectionAdvancer(events), FixedCheckpointPolicy.HOME_DEFAULT,
                CLOCK, DerivedPublishGate.unbounded());
        projection.setMode(SubscriberMode.LIVE);
        return projection;
    }

    private EventEnvelope reported(Instant eventTime) {
        return envelope(EventTypes.STATE_REPORTED, eventTime,
                new StateReportedEvent("power_w", "80.0", "W", null, null));
    }

    private EventEnvelope changed(Instant eventTime, String value) {
        return envelope(EventTypes.STATE_CHANGED, eventTime, new StateChangedEvent(
                "power_w", null, new StringValue(value), EventId.of(new Ulid(0x5CL, position))));
    }

    private EventEnvelope availability(Instant eventTime) {
        return envelope(EventTypes.AVAILABILITY_CHANGED, eventTime,
                new AvailabilityChangedEvent("online", "offline"));
    }

    /** An inbound envelope whose ingest time trails its event time by 2 s. */
    private EventEnvelope envelope(String eventType, Instant eventTime, DomainEvent payload) {
        position++;
        EventId eventId = EventId.of(new Ulid(0x0E0L, position));
        return new EventEnvelope(eventId, eventType, 1, eventTime.plusSeconds(2), eventTime,
                subject, position, position, EventPriority.NORMAL, EventOrigin.PHYSICAL,
                List.of(EventCategory.DEVICE_STATE), CausalContext.root(eventId.value()), null,
                payload);
    }

    private static CapabilityInstance instanceOf(Capability capability) {
        return new CapabilityInstance(capability.capabilityId(), capability.version(),
                capability.namespace(), 0, capability.attributeSchemas(),
                capability.commandDefinitions(), capability.confirmationPolicy());
    }
}
