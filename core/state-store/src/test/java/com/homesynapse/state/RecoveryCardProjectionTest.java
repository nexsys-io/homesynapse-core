/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.homesynapse.device.Entity;
import com.homesynapse.device.EntityRole;
import com.homesynapse.device.EntityType;
import com.homesynapse.device.InMemoryEntityRegistry;
import com.homesynapse.event.AvailabilityChangedEvent;
import com.homesynapse.event.AvailabilityContractDeclaredEvent;
import com.homesynapse.event.DegradedEvent;
import com.homesynapse.event.DomainEvent;
import com.homesynapse.event.EventDraft;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventOrigin;
import com.homesynapse.event.EventPage;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventStore;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.ProbeAnsweredEvent;
import com.homesynapse.event.StateReportedEvent;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.event.bus.SubscriberMode;
import com.homesynapse.event.test.InMemoryEventStore;
import com.homesynapse.platform.identity.DeviceId;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.Ulid;
import com.homesynapse.state.test.InMemoryViewCheckpointStore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AVAIL-API-1 — the recovery-card projection v1 (AMD-103 R-F, R-G): the per-device view keyed
 * by {@link DeviceId} over the three types, the newest record winning; the apply path that
 * never drops in COLD; the coupled checkpoint after every applied row; LIVE/not-LIVE; the
 * {@code java.base} view codec (state-store carries no Jackson); V-2 the merge order and the
 * degraded row; V-3 the state projection unmoved by the two DEVICE-subject types; V-4 a store
 * that throws on {@code readByType} leaves the projection not LIVE.
 */
@DisplayName("RecoveryCardProjection — the recovery-card view v1 (AVAIL-API-1 V-2, V-3, V-4)")
final class RecoveryCardProjectionTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-10-11T12:00:00Z"), ZoneOffset.UTC);
    private static final DeviceId D1 = DeviceId.parse("01JAAAAAAAAAAAAAAAAAAAAAD1");
    private static final DeviceId D2 = DeviceId.parse("01JAAAAAAAAAAAAAAAAAAAAAD2");
    private static final EntityId E1 = EntityId.parse("01JAAAAAAAAAAAAAAAAAAAAAE1");
    private static final EntityId E2 = EntityId.parse("01JAAAAAAAAAAAAAAAAAAAAAE2");
    private static final EntityId ORPHAN = EntityId.parse("01JAAAAAAAAAAAAAAAAAAAAAE9");
    private static final EntityId HELPER = EntityId.parse("01JAAAAAAAAAAAAAAAAAAAAAE8");
    private static final Instant T0 = Instant.parse("2026-10-11T10:00:00Z");

    private static final AvailabilityContractDeclaredEvent METERED =
            new AvailabilityContractDeclaredEvent("mains-metered", 600, 660, 2, 3600);
    private static final AvailabilityContractDeclaredEvent FLOOR =
            new AvailabilityContractDeclaredEvent("mains-floor", null, 60, 2, 3600);
    private static final AvailabilityContractDeclaredEvent PASSIVE =
            new AvailabilityContractDeclaredEvent("passive", null, 90_000, 2, null);

    private InMemoryEventStore store;
    private InMemoryViewCheckpointStore checkpoints;
    private InMemoryEntityRegistry registry;
    private RecoveryCardProjection projection;

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    RecoveryCardProjectionTest() {
    }

    @BeforeEach
    void setUp() {
        store = new InMemoryEventStore(CLOCK);
        checkpoints = new InMemoryViewCheckpointStore(CLOCK);
        registry = new InMemoryEntityRegistry();
        registry.createEntity(entity(E1, D1));
        registry.createEntity(entity(E2, D2));
        registry.createEntity(entity(HELPER, null));
        projection = new RecoveryCardProjection(checkpoints,
                AtomicCheckpointSink.viewOnly(checkpoints), () -> registry, CLOCK);
    }

    static Entity entity(EntityId entityId, DeviceId deviceId) {
        return new Entity(entityId, "entity-" + entityId, EntityType.LIGHT, "Entity " + entityId,
                deviceId, 0, null, true, List.of(), List.of(), EntityRole.PRIMARY, Instant.EPOCH);
    }

    private EventEnvelope probe(DeviceId device, Instant at, int answered, int missed) {
        return publish(EventTypes.PROBE_ANSWERED, SubjectRef.device(device), EventPriority.DIAGNOSTIC,
                new ProbeAnsweredEvent(at, at.minusSeconds(3600), answered, missed, 200, -45,
                        at.minusSeconds(30)), at);
    }

    private EventEnvelope contract(DeviceId device, AvailabilityContractDeclaredEvent contract,
            Instant at) {
        return publish(EventTypes.AVAILABILITY_CONTRACT_DECLARED, SubjectRef.device(device),
                EventPriority.NORMAL, contract, at);
    }

    private EventEnvelope availability(EntityId entity, Instant at) {
        return publish(EventTypes.AVAILABILITY_CHANGED, SubjectRef.entity(entity),
                EventPriority.CRITICAL, new AvailabilityChangedEvent("online", "offline",
                        "ping_timeout", at, null, null, null), at);
    }

    private EventEnvelope filler(EntityId entity) {
        return publish(EventTypes.STATE_REPORTED, SubjectRef.entity(entity),
                EventPriority.DIAGNOSTIC,
                new StateReportedEvent("temperature_c", "21.5", null, null, null), null);
    }

    private EventEnvelope publish(String type, SubjectRef subject, EventPriority priority,
            DomainEvent payload, Instant eventTime) {
        try {
            return store.publishRoot(new EventDraft(type, 1, eventTime, subject, priority,
                    EventOrigin.INTEGRATION, payload, null, null));
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    @Test
    @DisplayName("the identity: SUBSCRIBER_ID recovery-card-projection; the filter matches exactly "
            + "the three types")
    void identityAndFilter() {
        assertThat(RecoveryCardProjection.SUBSCRIBER_ID).isEqualTo("recovery-card-projection");
        assertThat(RecoveryCardProjection.EVENT_TYPES).containsExactly(EventTypes.PROBE_ANSWERED,
                EventTypes.AVAILABILITY_CONTRACT_DECLARED, EventTypes.AVAILABILITY_CHANGED);
        assertThat(RecoveryCardProjection.subscriptionFilter().matches(probe(D1, T0, 1, 0)))
                .isTrue();
        assertThat(RecoveryCardProjection.subscriptionFilter().matches(contract(D1, FLOOR, T0)))
                .isTrue();
        assertThat(RecoveryCardProjection.subscriptionFilter().matches(availability(E1, T0)))
                .isTrue();
        assertThat(RecoveryCardProjection.subscriptionFilter().matches(filler(E1))).isFalse();
    }

    @Test
    @DisplayName("P-1: onEvent applies each of the three types onto the device's record — the "
            + "probe's fields, the contract's fields with its instant, the newest "
            + "availability_changed's instant through the entity's owning device — and writes "
            + "the coupled checkpoint at the row's position after EVERY applied row")
    void applyEachType_andCheckpointAfterEveryRow() {
        EventEnvelope p = probe(D1, T0, 55, 1);
        projection.onEvent(p);
        assertThat(projection.position()).isEqualTo(p.globalPosition());
        assertThat(checkpoints.readLatestCheckpoint(RecoveryCardProjection.SUBSCRIBER_ID))
                .map(CheckpointRecord::position).contains(p.globalPosition());

        EventEnvelope c = contract(D1, METERED, T0.plusSeconds(10));
        projection.onEvent(c);
        EventEnvelope a = availability(E1, T0.plusSeconds(20));
        projection.onEvent(a);

        RecoveryCardRecord record = projection.recordFor(D1).orElseThrow();
        assertThat(record.probeAt()).isEqualTo(T0);
        assertThat(record.probeWindowStart()).isEqualTo(T0.minusSeconds(3600));
        assertThat(record.probesAnswered()).isEqualTo(55);
        assertThat(record.probesMissed()).isEqualTo(1);
        assertThat(record.lqi()).isEqualTo(200);
        assertThat(record.rssiDbm()).isEqualTo(-45);
        assertThat(record.linkAt()).isEqualTo(T0.minusSeconds(30));
        assertThat(record.availabilityClass()).isEqualTo("mains-metered");
        assertThat(record.reportIntervalSeconds()).isEqualTo(600);
        assertThat(record.silenceLimitSeconds()).isEqualTo(660);
        assertThat(record.missesToDark()).isEqualTo(2);
        assertThat(record.recordIntervalSeconds()).isEqualTo(3600);
        assertThat(record.contractAt()).isEqualTo(T0.plusSeconds(10));
        assertThat(record.availabilityChangedAt()).isEqualTo(T0.plusSeconds(20));
        assertThat(projection.position()).isEqualTo(a.globalPosition());
        assertThat(checkpoints.readLatestCheckpoint(RecoveryCardProjection.SUBSCRIBER_ID))
                .map(CheckpointRecord::position).contains(a.globalPosition());
        assertThat(projection.recordFor(D2)).isEmpty();
    }

    @Test
    @DisplayName("V-2: the newest record wins — a probe_answered older than the newest for its "
            + "device (an earlier at at a later position) never overwrites it; the same for the "
            + "contract and the availability_changed instant")
    void newestWins_anOlderRowNeverOverwrites() {
        projection.onEvent(probe(D1, T0.plusSeconds(100), 5, 0));
        projection.onEvent(probe(D1, T0, 1, 0));
        projection.onEvent(contract(D1, METERED, T0.plusSeconds(100)));
        projection.onEvent(contract(D1, FLOOR, T0));
        projection.onEvent(availability(E1, T0.plusSeconds(100)));
        projection.onEvent(availability(E1, T0));

        RecoveryCardRecord record = projection.recordFor(D1).orElseThrow();
        assertThat(record.probeAt()).isEqualTo(T0.plusSeconds(100));
        assertThat(record.probesAnswered()).isEqualTo(5);
        assertThat(record.availabilityClass()).isEqualTo("mains-metered");
        assertThat(record.contractAt()).isEqualTo(T0.plusSeconds(100));
        assertThat(record.availabilityChangedAt()).isEqualTo(T0.plusSeconds(100));

        projection.onEvent(probe(D1, T0.plusSeconds(200), 2, 1));
        assertThat(projection.recordFor(D1).orElseThrow().probeAt())
                .as("a newer row replaces").isEqualTo(T0.plusSeconds(200));
    }

    @Test
    @DisplayName("V-2: a DegradedEvent under a listed type is skipped — the record unchanged, the "
            + "position and the checkpoint advanced past it")
    void degradedRow_skipped_positionAdvances() {
        projection.onEvent(probe(D1, T0, 3, 0));
        EventEnvelope degraded = publish(EventTypes.PROBE_ANSWERED, SubjectRef.device(D1),
                EventPriority.DIAGNOSTIC,
                new DegradedEvent(EventTypes.PROBE_ANSWERED, 1, "{}", "test: a bad row"), T0);

        projection.onEvent(degraded);

        assertThat(projection.recordFor(D1).orElseThrow().probesAnswered()).isEqualTo(3);
        assertThat(projection.position()).isEqualTo(degraded.globalPosition());
        assertThat(checkpoints.readLatestCheckpoint(RecoveryCardProjection.SUBSCRIBER_ID))
                .map(CheckpointRecord::position).contains(degraded.globalPosition());
    }

    @Test
    @DisplayName("P-4: an availability_changed whose entity the registry lacks, or whose entity "
            + "owns no device, attributes to no record")
    void unknownOrHelperEntity_attributesNothing() {
        projection.onEvent(availability(ORPHAN, T0));
        projection.onEvent(availability(HELPER, T0));

        assertThat(projection.recordFor(D1)).isEmpty();
        assertThat(projection.recordFor(D2)).isEmpty();
        assertThat(projection.view()).isEmpty();
    }

    @Test
    @DisplayName("P-5: askedCount is the count of devices whose NEWEST contract names an asked "
            + "class — the projection's own count over its view")
    void askedCount_followsTheNewestContracts() {
        assertThat(projection.askedCount()).isZero();
        projection.onEvent(contract(D1, METERED, T0));
        projection.onEvent(contract(D2, PASSIVE, T0));
        assertThat(projection.askedCount()).isEqualTo(1);

        projection.onEvent(contract(D2, FLOOR, T0.plusSeconds(1)));
        assertThat(projection.askedCount()).isEqualTo(2);

        projection.onEvent(contract(D1, PASSIVE, T0.plusSeconds(2)));
        assertThat(projection.askedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("P-6: not LIVE until onCaughtUp(); SUSPENDED turns it off; the view is read "
            + "either way (the endpoint gates by isLive)")
    void liveFollowsTheBusHandoff() {
        projection.onEvent(probe(D1, T0, 1, 0));
        assertThat(projection.isLive()).isFalse();
        assertThat(projection.recordFor(D1)).isPresent();

        projection.setMode(SubscriberMode.LIVE);
        projection.onCaughtUp();
        assertThat(projection.isLive()).isTrue();

        projection.setMode(SubscriberMode.SUSPENDED);
        assertThat(projection.isLive()).isFalse();
    }

    @Test
    @DisplayName("P-7: the view codec round-trips every field shape — full, probe-only, "
            + "contract-only, empty — with the position; corrupt bytes fail loudly")
    void codecRoundTrip() {
        RecoveryCardRecord full = RecoveryCardRecord.empty(D1)
                .withProbe(new ProbeAnsweredEvent(T0, T0.minusSeconds(3600), 55, 1, 200, -45,
                        T0.minusSeconds(30)))
                .withContract(METERED, T0.plusSeconds(10))
                .withAvailabilityChangedAt(T0.plusSeconds(20));
        RecoveryCardRecord probeOnly = RecoveryCardRecord.empty(D2)
                .withProbe(new ProbeAnsweredEvent(T0, T0, 1, 0, null, null, null));
        DeviceId d3 = DeviceId.parse("01JAAAAAAAAAAAAAAAAAAAAAD3");
        RecoveryCardRecord contractOnly = RecoveryCardRecord.empty(d3).withContract(PASSIVE, T0);
        DeviceId d4 = DeviceId.parse("01JAAAAAAAAAAAAAAAAAAAAAD4");
        Map<DeviceId, RecoveryCardRecord> view = Map.of(D1, full, D2, probeOnly, d3, contractOnly,
                d4, RecoveryCardRecord.empty(d4));

        byte[] bytes = RecoveryCardProjection.encodeView(view, 4_242L);
        RecoveryCardProjection.ViewSnapshot decoded = RecoveryCardProjection.decodeView(bytes);

        assertThat(decoded.position()).isEqualTo(4_242L);
        assertThat(decoded.view()).containsExactlyInAnyOrderEntriesOf(view);
        assertThatThrownBy(() -> RecoveryCardProjection.decodeView(new byte[] {1, 2, 3}))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> RecoveryCardProjection.decodeView(
                java.util.Arrays.copyOf(bytes, bytes.length - 7)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("V-4: a store that throws on readByType — catchUp propagates, the projection "
            + "stays not LIVE at position 0 with an empty view and writes no checkpoint")
    void throwingStore_catchUpPropagates_notLive() {
        EventStore throwing = new ThrowingStore(store);

        assertThatThrownBy(() -> projection.catchUp(throwing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("readByType unavailable");

        assertThat(projection.isLive()).isFalse();
        assertThat(projection.position()).isZero();
        assertThat(projection.view()).isEmpty();
        assertThat(checkpoints.readLatestCheckpoint(RecoveryCardProjection.SUBSCRIBER_ID))
                .isEmpty();
    }

    @Test
    @DisplayName("P-11: the catch-up reads ONLY the three types through readByType (never "
            + "readFrom) and applies them in global order — the per-type cursors merged")
    void catchUp_readsOnlyTheThreeTypes_inGlobalOrder() {
        for (int i = 0; i < 30; i++) {
            filler(E1);
        }
        contract(D1, FLOOR, T0);
        filler(E2);
        probe(D1, T0.plusSeconds(10), 1, 0);
        availability(E1, T0.plusSeconds(20));
        for (int i = 0; i < 30; i++) {
            filler(E2);
        }
        contract(D1, METERED, T0.plusSeconds(30));
        probe(D2, T0.plusSeconds(40), 2, 0);
        CountingStore counting = new CountingStore(store);

        long applied = projection.catchUp(counting);

        assertThat(applied).isEqualTo(5L);
        assertThat(counting.readFromCalls).isZero();
        assertThat(counting.typesRead).containsExactlyInAnyOrder(EventTypes.PROBE_ANSWERED,
                EventTypes.AVAILABILITY_CONTRACT_DECLARED, EventTypes.AVAILABILITY_CHANGED);
        assertThat(projection.position()).isEqualTo(store.latestPosition());
        RecoveryCardRecord d1 = projection.recordFor(D1).orElseThrow();
        assertThat(d1.availabilityClass()).as("the later contract wins").isEqualTo("mains-metered");
        assertThat(d1.probeAt()).isEqualTo(T0.plusSeconds(10));
        assertThat(d1.availabilityChangedAt()).isEqualTo(T0.plusSeconds(20));
        assertThat(projection.recordFor(D2).orElseThrow().probesAnswered()).isEqualTo(2);
        assertThat(checkpoints.readLatestCheckpoint(RecoveryCardProjection.SUBSCRIBER_ID))
                .map(CheckpointRecord::position).contains(store.latestPosition());
    }

    @Test
    @DisplayName("V-3: the state projection on a store carrying the two DEVICE-subject types "
            + "applies nothing for them and reaches LIVE — stateVersion unchanged for every entity")
    void stateProjection_ignoresTheDeviceSubjectTypes() {
        InMemoryStateStore stateStore = new InMemoryStateStore();
        InMemoryViewCheckpointStore stateCheckpoints = new InMemoryViewCheckpointStore(CLOCK);
        StateProjection stateProjection = StateProjection.create(
                new ProjectionId("state_projection"), 1, stateCheckpoints,
                StateCheckpointSource.stub(), AtomicCheckpointSink.viewOnly(stateCheckpoints),
                stateStore, DerivationRule.production(), store,
                new InMemoryProjectionAdvancer(store), FixedCheckpointPolicy.HOME_DEFAULT,
                CLOCK, DerivedPublishGate.unbounded());
        stateProjection.setMode(SubscriberMode.REPLAY);
        EventEnvelope report = filler(E1);
        stateProjection.onEvent(report);
        long versionBefore = stateStore.get(E1).orElseThrow().stateVersion();

        EventEnvelope p = probe(D1, T0, 1, 0);
        EventEnvelope c = contract(D1, METERED, T0);
        stateProjection.onEvent(p);
        stateProjection.onEvent(c);
        stateProjection.setMode(SubscriberMode.LIVE);
        stateProjection.onCaughtUp();

        assertThat(stateStore.get(E1).orElseThrow().stateVersion()).isEqualTo(versionBefore);
        assertThat(stateStore.get(new EntityId(D1.value())))
                .as("a DEVICE subject never becomes an entity").isEmpty();
        assertThat(stateProjection.cursorPosition())
                .as("the cursor passes the two rows").isEqualTo(c.globalPosition());
        assertThat(stateProjection.currentMode()).isEqualTo(SubscriberMode.LIVE);
    }

    /** A store whose type read throws — V-4's instrument. */
    static final class ThrowingStore implements EventStore {
        private final EventStore delegate;

        ThrowingStore(EventStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public EventPage readFrom(long afterPosition, int maxCount) {
            return delegate.readFrom(afterPosition, maxCount);
        }

        @Override
        public EventPage readBySubject(SubjectRef subject, long afterSequence, int maxCount) {
            return delegate.readBySubject(subject, afterSequence, maxCount);
        }

        @Override
        public List<EventEnvelope> readByCorrelation(Ulid correlationId) {
            return delegate.readByCorrelation(correlationId);
        }

        @Override
        public EventPage readByType(String eventType, long afterPosition, int maxCount) {
            throw new IllegalStateException("readByType unavailable: " + eventType);
        }

        @Override
        public EventPage readByTimeRange(Instant from, Instant to, long afterPosition,
                int maxCount) {
            return delegate.readByTimeRange(from, to, afterPosition, maxCount);
        }

        @Override
        public long latestPosition() {
            return delegate.latestPosition();
        }
    }

    /** A store that counts the reads — P-11's instrument (the registry's R-3 form). */
    static final class CountingStore implements EventStore {
        private final EventStore delegate;
        int readFromCalls;
        final List<String> typesRead = new ArrayList<>();

        CountingStore(EventStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public EventPage readFrom(long afterPosition, int maxCount) {
            readFromCalls++;
            return delegate.readFrom(afterPosition, maxCount);
        }

        @Override
        public EventPage readBySubject(SubjectRef subject, long afterSequence, int maxCount) {
            return delegate.readBySubject(subject, afterSequence, maxCount);
        }

        @Override
        public List<EventEnvelope> readByCorrelation(Ulid correlationId) {
            return delegate.readByCorrelation(correlationId);
        }

        @Override
        public EventPage readByType(String eventType, long afterPosition, int maxCount) {
            if (!typesRead.contains(eventType)) {
                typesRead.add(eventType);
            }
            return delegate.readByType(eventType, afterPosition, maxCount);
        }

        @Override
        public EventPage readByTimeRange(Instant from, Instant to, long afterPosition,
                int maxCount) {
            return delegate.readByTimeRange(from, to, afterPosition, maxCount);
        }

        @Override
        public long latestPosition() {
            return delegate.latestPosition();
        }
    }
}
