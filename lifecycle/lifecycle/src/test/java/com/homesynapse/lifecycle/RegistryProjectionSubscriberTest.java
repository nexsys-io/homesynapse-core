/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.lifecycle;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.homesynapse.device.Capability;
import com.homesynapse.device.CapabilityInstance;
import com.homesynapse.device.Device;
import com.homesynapse.device.DeviceRegistry;
import com.homesynapse.device.Entity;
import com.homesynapse.device.EntityType;
import com.homesynapse.device.HardwareIdentifier;
import com.homesynapse.device.InMemoryDeviceRegistry;
import com.homesynapse.device.InMemoryEntityRegistry;
import com.homesynapse.device.RegistryEventMapper;
import com.homesynapse.device.RegistryProjection;
import com.homesynapse.device.StandardCapabilities;
import com.homesynapse.event.CausalContext;
import com.homesynapse.event.DegradedEvent;
import com.homesynapse.event.DeviceRemovedEvent;
import com.homesynapse.event.DomainEvent;
import com.homesynapse.event.EventCategory;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventId;
import com.homesynapse.event.EventOrigin;
import com.homesynapse.event.EventPage;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventStore;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.StateReportedEvent;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.integration.CapabilityAdded;
import com.homesynapse.platform.identity.DeviceId;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.IntegrationId;
import com.homesynapse.platform.identity.Ulid;
import com.homesynapse.platform.identity.UlidFactory;
import com.homesynapse.test.TestClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * IR-67 T11 — the registry projection's bus consumer carries {@code capability.added}
 * beside the three registration/removal types, and routes it through the single
 * apply path (REG-INV-1): replay and live delivery run the identical apply; an
 * entity the registry lacks is the orphan no-op with ONE WARN (the DP-7 posture).
 *
 * <p>REGISTRY-COLD-1 (IR-148) R-1..R-5 — {@code rebuildFromIndex}: the boot rebuild reads the
 * four registry types through the store's type index up to the head and feeds them to the SAME
 * {@code onEvent} the bus calls, merged into log order. The store under these tests is an
 * in-memory {@link EventStore} over hand-built envelopes (the read seam, counted by a decorator
 * in R-3 — rows, never time); the registries are real in-memory ones behind a real
 * {@link RegistryProjection}.</p>
 */
@DisplayName("RegistryProjectionSubscriber — the capability.added consumer (IR-67) and the"
        + " index rebuild (REGISTRY-COLD-1)")
final class RegistryProjectionSubscriberTest {

    private static final DeviceId DEVICE_ID =
            DeviceId.parse("01JAAAAAAAAAAAAAAAAAAAAAD1");
    private static final EntityId ENTITY_ID =
            EntityId.parse("01JAAAAAAAAAAAAAAAAAAAAAE1");
    private static final IntegrationId INTEGRATION_ID =
            IntegrationId.parse("01JAAAAAAAAAAAAAAAAAAAAAD2");
    /** A second device, above R-2's head. */
    private static final DeviceId DEVICE_2_ID =
            DeviceId.parse("01JAAAAAAAAAAAAAAAAAAAAAD3");

    private static final List<String> REGISTRY_TYPES = List.of(
            EventTypes.DEVICE_REGISTERED,
            EventTypes.ENTITY_REGISTERED,
            EventTypes.DEVICE_REMOVED,
            EventTypes.CAPABILITY_ADDED);

    private static final String REBUILD_APPLY_FAILED = "registry.projection_rebuild_apply_failed: ";

    private TestClock clock;
    private InMemoryDeviceRegistry deviceRegistry;
    private InMemoryEntityRegistry entityRegistry;
    private RegistryProjection projection;
    private RegistryProjectionSubscriber subscriber;
    private ListAppender<ILoggingEvent> subscriberLog;

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    RegistryProjectionSubscriberTest() {
    }

    @BeforeEach
    void setUp() {
        clock = TestClock.createDefault();
        deviceRegistry = new InMemoryDeviceRegistry();
        entityRegistry = new InMemoryEntityRegistry();
        projection = new RegistryProjection(deviceRegistry, entityRegistry);
        subscriber = new RegistryProjectionSubscriber(projection, deviceRegistry,
                entityRegistry);
        // The entity the log carries: registered through the single apply path
        // with ONE capability, so an appended id is observable.
        projection.applyDeviceRegistered(RegistryEventMapper.toPayload(device()));
        projection.applyEntityRegistered(RegistryEventMapper.toPayload(
                entity(List.of(instance(StandardCapabilities.occupancy())))));
        subscriberLog = new ListAppender<>();
        subscriberLog.start();
        subscriberLogger().addAppender(subscriberLog);
    }

    @AfterEach
    void tearDown() {
        subscriberLogger().detachAppender(subscriberLog);
        subscriberLog.stop();
    }

    @Test
    @DisplayName("T11: the subscription filter carries capability.added beside the three "
            + "registration/removal types — replay AND live route through this subscriber")
    void filterCarriesCapabilityAdded() {
        assertThat(RegistryProjectionSubscriber.subscriptionFilter().eventTypes())
                .containsExactlyInAnyOrder(
                        EventTypes.DEVICE_REGISTERED,
                        EventTypes.ENTITY_REGISTERED,
                        EventTypes.DEVICE_REMOVED,
                        EventTypes.CAPABILITY_ADDED);
    }

    @Test
    @DisplayName("T11: onEvent with a CapabilityAdded payload appends the id to the "
            + "registry's entity through RegistryProjection.applyCapabilityAdded")
    void onEvent_capabilityAdded_appliesToTheEntity() {
        CapabilityInstance illuminance =
                instance(StandardCapabilities.illuminanceMeasurement());

        subscriber.onEvent(envelope(7L,
                new CapabilityAdded(INTEGRATION_ID, DEVICE_ID, ENTITY_ID, illuminance)));

        assertThat(entityRegistry.getEntity(ENTITY_ID).capabilities())
                .extracting(CapabilityInstance::capabilityId)
                .containsExactly("occupancy", "illuminance_measurement");
        assertThat(messages(Level.WARN)).as("no WARN on the happy path").isEmpty();
    }

    @Test
    @DisplayName("T11: a CapabilityAdded for an entity the registry lacks is the orphan "
            + "no-op — no throw, ONE registry.capability_added_orphan WARN naming the position")
    void onEvent_orphan_noThrow() {
        EntityId orphan = EntityId.parse("01JAAAAAAAAAAAAAAAAAAAAAE9");
        CapabilityInstance illuminance =
                instance(StandardCapabilities.illuminanceMeasurement());

        assertThatCode(() -> subscriber.onEvent(envelope(9L,
                new CapabilityAdded(INTEGRATION_ID, DEVICE_ID, orphan, illuminance))))
                .doesNotThrowAnyException();

        assertThat(messages(Level.WARN))
                .containsExactly("registry.capability_added_orphan: entity=" + orphan
                        + " capability=illuminance_measurement position=9");
        assertThat(entityRegistry.listAllEntities())
                .as("the orphan arm writes nothing")
                .hasSize(1);
        assertThat(entityRegistry.getEntity(ENTITY_ID).capabilities()).hasSize(1);
    }

    // ════════════════════════════════════════════════════════════════════════
    // REGISTRY-COLD-1 (IR-148) — rebuildFromIndex, R-1..R-5
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("R-1 ORDER: a log whose per-type order is not its global order — register D,"
            + " register E, remove D, register D again, capability.added on E, fillers between —"
            + " rebuilds to EXACTLY the registries onEvent builds over the same envelopes in"
            + " position order (a per-type concatenation would end with no device)")
    void rebuild_appliesInLogOrder_notPerTypeOrder() {
        CapabilityInstance illuminance = instance(StandardCapabilities.illuminanceMeasurement());
        List<EventEnvelope> log = List.of(
                registered(1L, device()),
                filler(2L),
                registered(3L, entity(List.of(instance(StandardCapabilities.occupancy())))),
                filler(4L),
                removed(5L, DEVICE_ID),
                registered(6L, device()),
                filler(7L),
                envelope(8L, new CapabilityAdded(INTEGRATION_ID, DEVICE_ID, ENTITY_ID, illuminance)),
                filler(9L));

        // The reference: the bus's own path — every matching row to onEvent, in log order.
        Rig reference = Rig.fresh();
        for (EventEnvelope row : log) {
            if (RegistryProjectionSubscriber.subscriptionFilter().matches(row)) {
                reference.subscriber().onEvent(row);
            }
        }
        assertThat(messages(Level.WARN)).as("the reference run's orphan WARN").hasSize(1);
        subscriberLog.list.clear();

        Rig rebuilt = Rig.fresh();
        long passed = rebuilt.subscriber().rebuildFromIndex(new InMemoryEventStore(log), 9L);

        assertThat(passed).as("the five registry rows, the orphan included").isEqualTo(5);
        assertThat(rebuilt.devices().listAllDevices())
                .containsExactlyInAnyOrderElementsOf(reference.devices().listAllDevices())
                .hasSize(1);
        assertThat(rebuilt.entities().listAllEntities())
                .containsExactlyInAnyOrderElementsOf(reference.entities().listAllEntities())
                .isEmpty();
        // The discriminator: in log order the re-registration (6) follows the removal (5), so D
        // stands; all device_registered rows before all device_removed rows would drop it.
        assertThat(rebuilt.devices().findDevice(DEVICE_ID)).isPresent();
        assertThat(messages(Level.WARN))
                .as("the orphan arm behaves as under the bus — ONE WARN naming position 8")
                .containsExactly("registry.capability_added_orphan: entity=" + ENTITY_ID
                        + " capability=illuminance_measurement position=8");
    }

    @Test
    @DisplayName("R-2 THE CUT: registry rows above head are not applied; the rebuild returns the"
            + " rows at or below head and lastAppliedPosition (the LIVE line's position=) ≤ head")
    void rebuild_cutsAtHead() {
        List<EventEnvelope> log = List.of(
                registered(1L, device()),
                filler(2L),
                registered(3L, entity(List.of(instance(StandardCapabilities.occupancy())))),
                filler(4L),
                registered(5L, device(DEVICE_2_ID, "00112233445566AA")),
                filler(6L));
        Rig rig = Rig.fresh();

        long passed = rig.subscriber().rebuildFromIndex(new InMemoryEventStore(log), 4L);

        assertThat(passed).isEqualTo(2);
        assertThat(rig.devices().findDevice(DEVICE_ID)).isPresent();
        assertThat(rig.devices().findDevice(DEVICE_2_ID)).as("position 5 is above head 4").isEmpty();
        assertThat(rig.entities().findEntity(ENTITY_ID)).isPresent();
        rig.subscriber().onCaughtUp();
        assertThat(messages(Level.INFO))
                .containsExactly("registry.projection_live: devices=1 entities=1 position=3");
    }

    @Test
    @DisplayName("R-3 THE ROWS-READ GUARD: 10,000 non-registry rows and 40 registry rows through"
            + " a counting store → zero readFrom calls, readByType only with the four types,"
            + " 40 envelopes returned — counted in rows, never in time")
    void rebuild_readsOnlyTheFourTypesThroughTheTypeIndex() {
        List<EventEnvelope> log = new ArrayList<>();
        long position = 0L;
        for (int i = 0; i < 20; i++) {
            for (int f = 0; f < 250; f++) {
                log.add(filler(++position));
            }
            DeviceId deviceId = new DeviceId(UlidFactory.generate(clock));
            log.add(registered(++position, device(deviceId, String.format("%016X", 0x1000L + i))));
            for (int f = 0; f < 250; f++) {
                log.add(filler(++position));
            }
            log.add(registered(++position, entity(new EntityId(UlidFactory.generate(clock)),
                    deviceId, 1, "ep" + i, List.of(instance(StandardCapabilities.occupancy())))));
        }
        assertThat(log).hasSize(10_040);
        CountingEventStore counting = new CountingEventStore(new InMemoryEventStore(log));
        Rig rig = Rig.fresh();

        long passed = rig.subscriber().rebuildFromIndex(counting, position);

        assertThat(passed).isEqualTo(40);
        assertThat(counting.readFromCalls).as("the whole-log page walk is never taken").isZero();
        assertThat(counting.otherReadCalls).as("no subject, correlation or time-range read").isZero();
        assertThat(counting.readByTypeTypes).isNotEmpty().allMatch(REGISTRY_TYPES::contains);
        assertThat(new HashSet<>(counting.readByTypeTypes)).containsExactlyInAnyOrderElementsOf(
                REGISTRY_TYPES);
        assertThat(counting.envelopesReturned).isEqualTo(40);
        assertThat(rig.devices().listAllDevices()).hasSize(20);
        assertThat(rig.entities().listAllEntities()).hasSize(20);
    }

    @Test
    @DisplayName("R-4: a registry-type row that failed decode arrives as a DegradedEvent, takes the"
            + " registry.projection_degraded_event WARN arm, and the rebuild does not throw")
    void rebuild_degradedRegistryRow_warnsAndContinues() {
        List<EventEnvelope> log = List.of(
                registered(1L, device()),
                envelope(2L, EventTypes.ENTITY_REGISTERED, SubjectRef.entity(ENTITY_ID),
                        new DegradedEvent(EventTypes.ENTITY_REGISTERED, 1,
                                "{\"entity_id\":\"not-a-ulid\"}", "decode failed")),
                filler(3L));
        Rig rig = Rig.fresh();

        assertThatCode(() -> rig.subscriber().rebuildFromIndex(new InMemoryEventStore(log), 3L))
                .doesNotThrowAnyException();

        assertThat(messages(Level.WARN))
                .singleElement().asString()
                .startsWith("registry.projection_degraded_event: type=entity_registered position=2 "
                        + "reason=decode failed");
        assertThat(rig.devices().listAllDevices()).hasSize(1);
        assertThat(rig.entities().listAllEntities()).isEmpty();
        assertThat(rig.subscriber().skipped()).as("a degraded row is applied, not skipped").isZero();
    }

    @Test
    @DisplayName("R-5 THE THROW ARM: one registry row whose onEvent throws a RuntimeException →"
            + " ONE registry.projection_rebuild_apply_failed WARN, that row skipped, every other"
            + " row applied, the rebuild returns with skipped()=1")
    void rebuild_applyThrows_oneWarn_rowSkipped_restApplied() {
        List<DeviceId> ids = sixDeviceIds();
        List<EventEnvelope> log = sixRegistrations(ids);
        Rig rig = Rig.fresh(new ThrowingDeviceRegistry(new InMemoryDeviceRegistry(),
                Set.of(ids.get(2))));

        long passed = rig.subscriber().rebuildFromIndex(new InMemoryEventStore(log), 6L);

        assertThat(passed).as("the throw arm's row was passed to onEvent").isEqualTo(6);
        assertThat(rig.subscriber().skipped()).isEqualTo(1);
        assertThat(messages(Level.WARN)).containsExactly(REBUILD_APPLY_FAILED
                + "type=device_registered position=3 cause=java.lang.IllegalStateException: "
                + "poisoned device " + ids.get(2));
        assertThat(rig.devices().listAllDevices()).hasSize(5);
        assertThat(rig.devices().findDevice(ids.get(2))).isEmpty();
    }

    @Test
    @DisplayName("R-5 THE FIFTH: five registry rows whose onEvent throws → IllegalStateException"
            + " naming the count and the position, the bus's circuit-breaker count retyped as"
            + " REBUILD_CRASH_THRESHOLD = 5; the row after the fifth is never applied")
    void rebuild_fiveApplyFailures_throw() {
        assertThat(RegistryProjectionSubscriber.REBUILD_CRASH_THRESHOLD)
                .as("the bus's CIRCUIT_BREAKER_THRESHOLD (SubscriberSupervisor), retyped")
                .isEqualTo(5);
        List<DeviceId> ids = sixDeviceIds();
        List<EventEnvelope> log = sixRegistrations(ids);
        Rig rig = Rig.fresh(new ThrowingDeviceRegistry(new InMemoryDeviceRegistry(),
                new HashSet<>(ids.subList(0, 5))));

        assertThatThrownBy(() -> rig.subscriber().rebuildFromIndex(new InMemoryEventStore(log), 6L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("5 ")
                .hasMessageContaining("position=5");

        assertThat(rig.subscriber().skipped()).isEqualTo(5);
        assertThat(messages(Level.WARN)).hasSize(5).allMatch(m -> m.startsWith(REBUILD_APPLY_FAILED));
        assertThat(rig.devices().listAllDevices()).as("the rebuild stops at the fifth").isEmpty();
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    /** Fresh registries behind a fresh projection and subscriber — one per rebuild under test. */
    private record Rig(DeviceRegistry devices, InMemoryEntityRegistry entities,
            RegistryProjectionSubscriber subscriber) {

        static Rig fresh() {
            return fresh(new InMemoryDeviceRegistry());
        }

        static Rig fresh(DeviceRegistry devices) {
            InMemoryEntityRegistry entities = new InMemoryEntityRegistry();
            RegistryProjection projection = new RegistryProjection(devices, entities);
            return new Rig(devices, entities,
                    new RegistryProjectionSubscriber(projection, devices, entities));
        }
    }

    /** An envelope built by hand — the {@code NotifyingEventPublisherTest} :164 form. */
    private EventEnvelope envelope(long position, CapabilityAdded payload) {
        return envelope(position, EventTypes.CAPABILITY_ADDED,
                SubjectRef.entity(payload.entityId()), payload);
    }

    private EventEnvelope envelope(long position, String eventType, SubjectRef subject,
            DomainEvent payload) {
        Ulid ulid = UlidFactory.generate(clock);
        return new EventEnvelope(
                EventId.of(ulid),
                eventType,
                1,
                clock.instant(),
                clock.instant(),
                subject,
                1L,
                position,
                EventPriority.NORMAL,
                EventOrigin.INTEGRATION,
                List.of(EventCategory.DEVICE_STATE),
                CausalContext.root(ulid),
                null,
                payload);
    }

    private EventEnvelope registered(long position, Device device) {
        return envelope(position, EventTypes.DEVICE_REGISTERED,
                SubjectRef.device(device.deviceId()), RegistryEventMapper.toPayload(device));
    }

    private EventEnvelope registered(long position, Entity entity) {
        return envelope(position, EventTypes.ENTITY_REGISTERED,
                SubjectRef.entity(entity.entityId()), RegistryEventMapper.toPayload(entity));
    }

    private EventEnvelope removed(long position, DeviceId deviceId) {
        return envelope(position, EventTypes.DEVICE_REMOVED, SubjectRef.device(deviceId),
                new DeviceRemovedEvent("registry-cold-1 test"));
    }

    /** A non-registry row — the log is mostly these. */
    private EventEnvelope filler(long position) {
        return envelope(position, EventTypes.STATE_REPORTED, SubjectRef.entity(ENTITY_ID),
                new StateReportedEvent("occupancy", "true", null, null, null));
    }

    private List<DeviceId> sixDeviceIds() {
        List<DeviceId> ids = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            ids.add(new DeviceId(UlidFactory.generate(clock)));
        }
        return ids;
    }

    /** Six {@code device_registered} rows at positions 1..6. */
    private List<EventEnvelope> sixRegistrations(List<DeviceId> ids) {
        List<EventEnvelope> log = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            log.add(registered(i + 1L, device(ids.get(i), String.format("%016X", 0x2000L + i))));
        }
        return log;
    }

    private static CapabilityInstance instance(Capability capability) {
        return new CapabilityInstance(capability.capabilityId(), capability.version(),
                capability.namespace(), 0, capability.attributeSchemas(),
                capability.commandDefinitions(), capability.confirmationPolicy());
    }

    private Device device() {
        return device(DEVICE_ID, "0011223344556677");
    }

    private Device device(DeviceId deviceId, String ieee) {
        return new Device(
                deviceId,
                "zigbee-" + ieee,
                "Hue motion",
                "Signify",
                "SML003",
                null,
                null,
                null,
                INTEGRATION_ID,
                null,
                null,
                List.of(),
                Set.of(new HardwareIdentifier("zigbee", ieee)),
                clock.instant());
    }

    private Entity entity(List<CapabilityInstance> capabilities) {
        return entity(ENTITY_ID, DEVICE_ID, 1, "ep1", capabilities);
    }

    private Entity entity(EntityId entityId, DeviceId deviceId, int endpoint, String slugSuffix,
            List<CapabilityInstance> capabilities) {
        return new Entity(
                entityId,
                "zigbee-0011223344556677-" + slugSuffix,
                EntityType.BINARY_SENSOR,
                "Hue motion",
                deviceId,
                endpoint,
                null,
                true,
                List.of(),
                capabilities,
                clock.instant());
    }

    private List<String> messages(Level level) {
        return subscriberLog.list.stream()
                .filter(event -> event.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private static Logger subscriberLogger() {
        return (Logger) LoggerFactory.getLogger(RegistryProjectionSubscriber.class);
    }

    // ── the read seam: an in-memory store and its counting decorator ────────

    /**
     * An {@link EventStore} over hand-built envelopes: {@code readFrom} and {@code readByType}
     * page by position exactly as the SQLite store does (ascending, {@code hasMore} when a row
     * matches beyond the page); the three reads the rebuild never makes throw.
     */
    static final class InMemoryEventStore implements EventStore {
        private final List<EventEnvelope> rows;

        InMemoryEventStore(List<EventEnvelope> rows) {
            this.rows = rows.stream()
                    .sorted(Comparator.comparingLong(EventEnvelope::globalPosition))
                    .toList();
        }

        @Override
        public EventPage readFrom(long afterPosition, int maxCount) {
            return page(rows.stream().filter(e -> e.globalPosition() > afterPosition),
                    afterPosition, maxCount);
        }

        @Override
        public EventPage readBySubject(SubjectRef subject, long afterSequence, int maxCount) {
            throw new UnsupportedOperationException("not a rebuild read");
        }

        @Override
        public List<EventEnvelope> readByCorrelation(Ulid correlationId) {
            throw new UnsupportedOperationException("not a rebuild read");
        }

        @Override
        public EventPage readByType(String eventType, long afterPosition, int maxCount) {
            return page(rows.stream().filter(e -> e.eventType().equals(eventType)
                    && e.globalPosition() > afterPosition), afterPosition, maxCount);
        }

        @Override
        public EventPage readByTimeRange(Instant from, Instant to, long afterPosition,
                int maxCount) {
            throw new UnsupportedOperationException("not a rebuild read");
        }

        @Override
        public long latestPosition() {
            return rows.isEmpty() ? 0L : rows.get(rows.size() - 1).globalPosition();
        }

        private static EventPage page(Stream<EventEnvelope> matching, long afterPosition,
                int maxCount) {
            List<EventEnvelope> all = matching.toList();
            List<EventEnvelope> events = all.subList(0, Math.min(maxCount, all.size()));
            long next = events.isEmpty() ? afterPosition
                    : events.get(events.size() - 1).globalPosition();
            return new EventPage(events, next, all.size() > events.size());
        }
    }

    /** Counts the rebuild's reads: which methods, which types, how many envelopes came back. */
    static final class CountingEventStore implements EventStore {
        private final EventStore delegate;
        int readFromCalls;
        int otherReadCalls;
        final List<String> readByTypeTypes = new ArrayList<>();
        long envelopesReturned;

        CountingEventStore(EventStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public EventPage readFrom(long afterPosition, int maxCount) {
            readFromCalls++;
            EventPage page = delegate.readFrom(afterPosition, maxCount);
            envelopesReturned += page.events().size();
            return page;
        }

        @Override
        public EventPage readBySubject(SubjectRef subject, long afterSequence, int maxCount) {
            otherReadCalls++;
            return delegate.readBySubject(subject, afterSequence, maxCount);
        }

        @Override
        public List<EventEnvelope> readByCorrelation(Ulid correlationId) {
            otherReadCalls++;
            return delegate.readByCorrelation(correlationId);
        }

        @Override
        public EventPage readByType(String eventType, long afterPosition, int maxCount) {
            readByTypeTypes.add(eventType);
            EventPage page = delegate.readByType(eventType, afterPosition, maxCount);
            envelopesReturned += page.events().size();
            return page;
        }

        @Override
        public EventPage readByTimeRange(Instant from, Instant to, long afterPosition,
                int maxCount) {
            otherReadCalls++;
            return delegate.readByTimeRange(from, to, afterPosition, maxCount);
        }

        @Override
        public long latestPosition() {
            return delegate.latestPosition();
        }
    }

    /** A registry whose {@code createDevice} throws for the poisoned ids — the throw arm's rig. */
    static final class ThrowingDeviceRegistry implements DeviceRegistry {
        private final DeviceRegistry delegate;
        private final Set<DeviceId> poisoned;

        ThrowingDeviceRegistry(DeviceRegistry delegate, Set<DeviceId> poisoned) {
            this.delegate = delegate;
            this.poisoned = Set.copyOf(poisoned);
        }

        @Override
        public Device getDevice(DeviceId deviceId) {
            return delegate.getDevice(deviceId);
        }

        @Override
        public Optional<Device> findDevice(DeviceId deviceId) {
            return delegate.findDevice(deviceId);
        }

        @Override
        public List<Device> listAllDevices() {
            return delegate.listAllDevices();
        }

        @Override
        public Device createDevice(Device device) {
            if (poisoned.contains(device.deviceId())) {
                throw new IllegalStateException("poisoned device " + device.deviceId());
            }
            return delegate.createDevice(device);
        }

        @Override
        public Device updateDevice(Device device) {
            return delegate.updateDevice(device);
        }

        @Override
        public void removeDevice(DeviceId deviceId) {
            delegate.removeDevice(deviceId);
        }

        @Override
        public Optional<Device> findByHardwareIdentifier(String namespace, String value) {
            return delegate.findByHardwareIdentifier(namespace, value);
        }
    }
}
