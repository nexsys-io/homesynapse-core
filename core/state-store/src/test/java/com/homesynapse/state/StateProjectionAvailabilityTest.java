/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * J1 / LINK-READ-2 — the projection's {@code availability_changed} arm carries the version-2
 * payload's reason, last-seen instant and link reading onto {@link EntityState}. The event is
 * the truth at its instant: a version-1 payload (the five additions {@code null}) or a null
 * reading triple leaves the fields {@code null} — the prior's values are NOT carried — while
 * every OTHER arm ({@code state_reported}, {@code state_changed}) carries the three forward
 * unchanged. Built on {@link StateProjectionStalenessTest}'s harness (the 12-parameter
 * {@code create}; staleness is not in play here).
 */
@DisplayName("StateProjection — availabilityReason · lastSeenAt · link from availability_changed "
        + "v2 (J1 / LINK-READ-2)")
final class StateProjectionAvailabilityTest {

    /** The projection clock — never an event-time below. */
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final Instant T = Instant.parse("2026-10-03T12:00:00Z");
    private static final Instant LAST_SEEN = Instant.parse("2026-10-03T11:58:00Z");
    private static final Instant LINK_AT = Instant.parse("2026-10-03T11:57:30Z");

    private final EntityId entityId = new EntityId(new Ulid(0x71L, 0x71L));
    private final SubjectRef subject = SubjectRef.entity(entityId);
    private InMemoryStateStore stateStore;
    private StateProjection projection;
    private long position;

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    StateProjectionAvailabilityTest() {
    }

    @BeforeEach
    void setUp() {
        stateStore = new InMemoryStateStore();
        position = 0L;
        InMemoryEventStore events = new InMemoryEventStore(CLOCK);
        InMemoryViewCheckpointStore checkpoints = new InMemoryViewCheckpointStore(CLOCK);
        projection = StateProjection.create(
                new ProjectionId("state_projection"), 1, checkpoints,
                StateCheckpointSource.stub(), AtomicCheckpointSink.viewOnly(checkpoints),
                stateStore, DerivationRule.production(), events,
                new InMemoryProjectionAdvancer(events), FixedCheckpointPolicy.HOME_DEFAULT,
                CLOCK, DerivedPublishGate.unbounded());
        projection.setMode(SubscriberMode.LIVE);
    }

    @Test
    @DisplayName("T7: a v2 payload sets availabilityReason, lastSeenAt and link from the event; "
            + "availability follows newStatus; stateVersion advances")
    void versionTwoPayload_carriesTheThreeFields() {
        projection.onEvent(availability(2, new AvailabilityChangedEvent("online", "offline",
                "ping_timeout", LAST_SEEN, 200, -45, LINK_AT)));

        EntityState state = stateStore.get(entityId).orElseThrow();
        assertThat(state.availability()).isEqualTo(Availability.UNAVAILABLE);
        assertThat(state.availabilityReason()).isEqualTo("ping_timeout");
        assertThat(state.lastSeenAt()).isEqualTo(LAST_SEEN);
        assertThat(state.link()).isEqualTo(new EntityLink(200, -45, LINK_AT));
        assertThat(state.stateVersion()).as("the cursor advances as today").isEqualTo(1L);
        assertThat(state.lastUpdated()).isEqualTo(T);
    }

    @Test
    @DisplayName("T7: a v1 payload (the five additions null) sets the three fields null; "
            + "availability and stateVersion move as before")
    void versionOnePayload_leavesTheThreeFieldsNull() {
        projection.onEvent(availability(1,
                new AvailabilityChangedEvent("unknown", "online", null, null, null, null, null)));

        EntityState state = stateStore.get(entityId).orElseThrow();
        assertThat(state.availability()).isEqualTo(Availability.AVAILABLE);
        assertThat(state.availabilityReason()).isNull();
        assertThat(state.lastSeenAt()).isNull();
        assertThat(state.link()).isNull();
        assertThat(state.stateVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("T7: the event is the truth at its instant — a later v2 payload with a NULL "
            + "reading triple leaves link null (the prior's reading is NOT carried), and its "
            + "own reason/lastSeenAt replace the prior's")
    void nullTripleAfterAReading_doesNotCarryThePriorReading() {
        projection.onEvent(availability(2, new AvailabilityChangedEvent("unknown", "online",
                "first_contact", LAST_SEEN, 200, -45, LINK_AT)));
        Instant later = LAST_SEEN.plusSeconds(90);
        projection.onEvent(availability(2, new AvailabilityChangedEvent("online", "offline",
                "silence_timeout", later, null, null, null)));

        EntityState state = stateStore.get(entityId).orElseThrow();
        assertThat(state.availability()).isEqualTo(Availability.UNAVAILABLE);
        assertThat(state.availabilityReason()).isEqualTo("silence_timeout");
        assertThat(state.lastSeenAt()).isEqualTo(later);
        assertThat(state.link())
                .as("a null triple is a value: the event carried no reading")
                .isNull();
        assertThat(state.stateVersion()).isEqualTo(2L);
    }

    @Test
    @DisplayName("T7: every other arm carries the three through — a state_reported and a "
            + "state_changed after the v2 event leave availabilityReason, lastSeenAt and link "
            + "exactly as the availability event set them")
    void otherArms_carryTheThreeFieldsThrough() {
        projection.onEvent(availability(2, new AvailabilityChangedEvent("unknown", "online",
                "first_contact", LAST_SEEN, 200, -45, LINK_AT)));

        projection.onEvent(reported(T.plusSeconds(10)));
        EntityState afterReport = stateStore.get(entityId).orElseThrow();
        assertThat(afterReport.lastReported()).isEqualTo(T.plusSeconds(10));
        assertThat(afterReport.availabilityReason()).isEqualTo("first_contact");
        assertThat(afterReport.lastSeenAt()).isEqualTo(LAST_SEEN);
        assertThat(afterReport.link()).isEqualTo(new EntityLink(200, -45, LINK_AT));

        projection.onEvent(changed(T.plusSeconds(20), "82.0"));
        EntityState afterChange = stateStore.get(entityId).orElseThrow();
        assertThat(afterChange.lastChanged()).isEqualTo(T.plusSeconds(20));
        assertThat(afterChange.availabilityReason()).isEqualTo("first_contact");
        assertThat(afterChange.lastSeenAt()).isEqualTo(LAST_SEEN);
        assertThat(afterChange.link()).isEqualTo(new EntityLink(200, -45, LINK_AT));
        assertThat(afterChange.stateVersion())
                .as("the cursor advanced (the production rule also derives a state_changed "
                        + "from the first report, so the count is not pinned here)")
                .isGreaterThan(afterReport.stateVersion());
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private EventEnvelope availability(int schemaVersion, AvailabilityChangedEvent payload) {
        return envelope(EventTypes.AVAILABILITY_CHANGED, schemaVersion, T, payload);
    }

    private EventEnvelope reported(Instant eventTime) {
        return envelope(EventTypes.STATE_REPORTED, 1, eventTime,
                new StateReportedEvent("power_w", "80.0", "W", null, null));
    }

    private EventEnvelope changed(Instant eventTime, String value) {
        return envelope(EventTypes.STATE_CHANGED, 2, eventTime, new StateChangedEvent(
                "power_w", null, new StringValue(value), EventId.of(new Ulid(0x5CL, position))));
    }

    /** An inbound envelope whose ingest time trails its event time by 2 s. */
    private EventEnvelope envelope(String eventType, int schemaVersion, Instant eventTime,
            DomainEvent payload) {
        position++;
        EventId eventId = EventId.of(new Ulid(0x0E1L, position));
        return new EventEnvelope(eventId, eventType, schemaVersion, eventTime.plusSeconds(2),
                eventTime, subject, position, position, EventPriority.NORMAL,
                EventOrigin.PHYSICAL, List.of(EventCategory.DEVICE_STATE),
                CausalContext.root(eventId.value()), null, payload);
    }
}
