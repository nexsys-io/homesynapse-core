/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homesynapse.event.AvailabilityContractDeclaredEvent;
import com.homesynapse.event.DegradedEvent;
import com.homesynapse.event.DomainEvent;
import com.homesynapse.event.EventDraft;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventOrigin;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventPublisher;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.ProbeAnsweredEvent;
import com.homesynapse.event.StateReportedEvent;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.event.bus.SubscriberMode;
import com.homesynapse.platform.identity.DeviceId;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.HomeId;
import com.homesynapse.platform.identity.Ulid;
import com.homesynapse.state.DerivationRule;
import com.homesynapse.state.DerivedPublishGate;
import com.homesynapse.state.EntityState;
import com.homesynapse.state.FixedCheckpointPolicy;
import com.homesynapse.state.ProjectionAdvancer;
import com.homesynapse.state.ProjectionId;
import com.homesynapse.state.StateProjection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

/**
 * AVAIL-API-1 E-6 — THE ROLLBACK TEST (AMD-103; D-v105-22: T-A's acceptance). Rows of both new
 * types are written through the current codec; the OLDER build — its registry the production
 * list ({@code SqlitePersistenceLifecycle} hands {@code AllEventClasses.ALL_EVENTS}'s shape to
 * {@code EventTypeRegistry}) MINUS the two classes, 58 of 60 — decodes each as
 * {@link DegradedEvent} (the codec's stage-1 fallback, unknown type), its all-types subscriber
 * (a {@link StateProjection} fed every envelope in log order, the bus's path modeled as
 * REGISTRY-COLD-1's K-2 modeled it) applies nothing for them and reaches LIVE, and
 * {@code latestPosition()} and a subsequent append are unaffected.
 *
 * <p>This is the proof at the CODEC and the ALL-TYPES SUBSCRIBER that the prior build decodes a
 * log carrying the two types and its state projection is unmoved; the BOOT itself is BC10's
 * rehearsal of the older build on the throwaway repo (D-v105-23).</p>
 */
@DisplayName("ProbeRecordRollbackIT — the older build decodes the two new types as DegradedEvent, "
        + "its state projection is unmoved, the log stays appendable (AVAIL-API-1 E-6)")
final class ProbeRecordRollbackIT {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-10-11T12:00:00Z"), ZoneOffset.UTC);
    private static final HomeId HOME_ID = HomeId.of(Ulid.parse("01JAAAAAAAAAAAAAAAAAAAAAAA"));
    private static final DeviceId DEVICE_ID = DeviceId.parse("01JAAAAAAAAAAAAAAAAAAAAAD1");
    private static final EntityId ENTITY_ID = EntityId.parse("01JAAAAAAAAAAAAAAAAAAAAAE1");
    private static final Instant AT = Instant.parse("2026-10-11T11:00:00Z");
    private static final Instant WINDOW_START = Instant.parse("2026-10-11T10:00:00Z");
    private static final Instant LINK_AT = Instant.parse("2026-10-11T10:59:30Z");

    /** The older build's registry: the production list minus the two AMD-103 classes. */
    static final List<Class<? extends DomainEvent>> OLDER_BUILD = AllEventClasses.ALL_EVENTS
            .stream()
            .filter(cls -> cls != ProbeAnsweredEvent.class
                    && cls != AvailabilityContractDeclaredEvent.class)
            .toList();

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    ProbeRecordRollbackIT() {
    }

    @Test
    @DisplayName("E-6: the current build writes both types; the older build decodes them as "
            + "DegradedEvent, its state projection applies nothing for them and reaches LIVE, "
            + "latestPosition() holds and an append lands; the current build then reads them typed")
    void olderBuildDecodesDegraded_projectionUnmoved_logAppendable(@TempDir Path tempDir)
            throws Exception {
        assertThat(AllEventClasses.ALL_EVENTS).as("R-E: the full registry").hasSize(60);
        assertThat(OLDER_BUILD).as("the older build's registry").hasSize(58);
        Path dbPath = tempDir.resolve("homesynapse-events.db");
        ProbeAnsweredEvent probe = new ProbeAnsweredEvent(AT, WINDOW_START, 55, 1, 200, -45,
                LINK_AT);
        AvailabilityContractDeclaredEvent contract =
                new AvailabilityContractDeclaredEvent("mains-metered", 600, 660, 2, 3600);

        // 1. The current build writes the log: an entity's report, the two DEVICE rows, a report.
        long head;
        try (PersistenceFactory current = start(dbPath, AllEventClasses.ALL_EVENTS)) {
            EventPublisher publisher = current.eventPublisher();
            publisher.publishRoot(stateReported("21.5"));
            publisher.publishRoot(new EventDraft(EventTypes.PROBE_ANSWERED, 1, AT,
                    SubjectRef.device(DEVICE_ID), EventPriority.DIAGNOSTIC,
                    EventOrigin.INTEGRATION, probe, null, null));
            publisher.publishRoot(new EventDraft(EventTypes.AVAILABILITY_CONTRACT_DECLARED, 1,
                    AT, SubjectRef.device(DEVICE_ID), EventPriority.NORMAL,
                    EventOrigin.INTEGRATION, contract, null, null));
            publisher.publishRoot(stateReported("21.6"));
            head = current.eventStore().latestPosition();
        }
        assertThat(head).isEqualTo(4L);

        // 2. The codec: the older registry decodes the current bytes as DegradedEvent.
        ObjectMapper mapper = PersistenceObjectMapper.create();
        EventTypeRegistry currentRegistry = new EventTypeRegistry(AllEventClasses.ALL_EVENTS);
        EventPayloadCodec currentCodec = new EventPayloadCodec(currentRegistry,
                JacksonWarmup.warmup(mapper, currentRegistry));
        EventTypeRegistry olderRegistry = new EventTypeRegistry(OLDER_BUILD);
        EventPayloadCodec olderCodec = new EventPayloadCodec(olderRegistry,
                JacksonWarmup.warmup(mapper, olderRegistry));
        assertThat(olderRegistry.size()).isEqualTo(58);
        assertThat(olderRegistry.classFor(EventTypes.PROBE_ANSWERED)).isEmpty();
        assertThat(olderRegistry.classFor(EventTypes.AVAILABILITY_CONTRACT_DECLARED)).isEmpty();
        assertDegraded(olderCodec.decode(EventTypes.PROBE_ANSWERED, 1, currentCodec.encode(probe)),
                EventTypes.PROBE_ANSWERED);
        assertDegraded(olderCodec.decode(EventTypes.AVAILABILITY_CONTRACT_DECLARED, 1,
                currentCodec.encode(contract)), EventTypes.AVAILABILITY_CONTRACT_DECLARED);
        assertThat(currentCodec.decode(EventTypes.PROBE_ANSWERED, 1, currentCodec.encode(probe)))
                .as("the current build round-trips the record").isEqualTo(probe);
        assertThat(currentCodec.decode(EventTypes.AVAILABILITY_CONTRACT_DECLARED, 1,
                currentCodec.encode(contract))).isEqualTo(contract);

        // 3. The older build opens the same store: degraded rows, an unmoved projection, an append.
        try (PersistenceFactory older = start(dbPath, OLDER_BUILD)) {
            List<EventEnvelope> rows = older.eventStore().readFrom(0L, 100).events();
            assertThat(rows).hasSize(4);
            assertThat(rows.get(0).payload()).isInstanceOf(StateReportedEvent.class);
            assertDegraded(rows.get(1).payload(), EventTypes.PROBE_ANSWERED);
            assertDegraded(rows.get(2).payload(), EventTypes.AVAILABILITY_CONTRACT_DECLARED);
            assertThat(rows.get(1).subjectRef()).isEqualTo(SubjectRef.device(DEVICE_ID));
            assertThat(rows.get(3).payload()).isInstanceOf(StateReportedEvent.class);
            assertThat(older.eventStore().latestPosition()).isEqualTo(head);

            StateProjection projection = StateProjection.create(
                    new ProjectionId("state_projection"), 1,
                    older.viewCheckpointStore(), older.stateCheckpointSource(),
                    older.atomicCheckpointSink(), older.stateStore(),
                    DerivationRule.production(), older.eventPublisher(),
                    inertAdvancer(), FixedCheckpointPolicy.HOME_DEFAULT,
                    FIXED_CLOCK, DerivedPublishGate.unbounded());
            projection.setMode(SubscriberMode.REPLAY);
            for (EventEnvelope row : rows) {
                projection.onEvent(row);
            }
            projection.setMode(SubscriberMode.LIVE);
            projection.onCaughtUp();

            assertThat(projection.currentMode()).isEqualTo(SubscriberMode.LIVE);
            assertThat(projection.cursorPosition())
                    .as("the cursor passed the two DEVICE rows").isEqualTo(head);
            EntityState entity = older.stateStore().get(ENTITY_ID).orElseThrow();
            assertThat(entity.stateVersion())
                    .as("two state_reported rows; the two DEVICE rows applied nothing")
                    .isEqualTo(2L);
            assertThat(older.stateStore().get(new EntityId(DEVICE_ID.value())))
                    .as("a DEVICE subject never becomes an entity").isEmpty();
            assertThat(older.eventStore().latestPosition())
                    .as("the projection in REPLAY appended nothing").isEqualTo(head);

            EventEnvelope appended = older.eventPublisher().publishRoot(stateReported("21.7"));
            assertThat(appended.globalPosition()).isEqualTo(head + 1);
        }

        // 4. The current build re-opens: the two rows decode typed; the older build's append stands.
        try (PersistenceFactory current = start(dbPath, AllEventClasses.ALL_EVENTS)) {
            List<EventEnvelope> rows = current.eventStore().readFrom(0L, 100).events();
            assertThat(rows).hasSize(5);
            assertThat(rows.get(1).payload()).isEqualTo(probe);
            assertThat(rows.get(1).priority()).isEqualTo(EventPriority.DIAGNOSTIC);
            assertThat(rows.get(2).payload()).isEqualTo(contract);
            assertThat(rows.get(2).priority()).isEqualTo(EventPriority.NORMAL);
            assertThat(current.eventStore().latestPosition()).isEqualTo(head + 1);
        }
    }

    private static void assertDegraded(DomainEvent payload, String eventType) {
        assertThat(payload).isInstanceOf(DegradedEvent.class);
        DegradedEvent degraded = (DegradedEvent) payload;
        assertThat(degraded.eventType()).isEqualTo(eventType);
        assertThat(degraded.schemaVersion()).isEqualTo(1);
        assertThat(degraded.failureReason()).contains("Unknown event type");
    }

    private static PersistenceFactory start(Path dbPath,
            List<Class<? extends DomainEvent>> eventClasses) {
        return PersistenceFactory.start(dbPath, PersistenceConfig.HOME_DEFAULT, FIXED_CLOCK,
                HOME_ID, eventClasses, null);
    }

    private static EventDraft stateReported(String value) throws IOException {
        return new EventDraft(EventTypes.STATE_REPORTED, 1, null, SubjectRef.entity(ENTITY_ID),
                EventPriority.DIAGNOSTIC, EventOrigin.DEVICE_AUTONOMOUS,
                new StateReportedEvent("temperature_c", value, null, null, null), null, null);
    }

    /** {@code onEvent} never touches the advancer; the batch path is not under test. */
    private static ProjectionAdvancer inertAdvancer() {
        return (fromPosition, maxRows, processor) -> {
            throw new UnsupportedOperationException("the batch path is not under test");
        };
    }
}
