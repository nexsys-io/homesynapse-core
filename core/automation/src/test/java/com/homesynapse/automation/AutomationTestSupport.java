/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.homesynapse.device.Area;
import com.homesynapse.device.AreaRegistry;
import com.homesynapse.device.Capability;
import com.homesynapse.device.CapabilityInstance;
import com.homesynapse.device.Device;
import com.homesynapse.device.DeviceRegistry;
import com.homesynapse.device.Entity;
import com.homesynapse.device.EntityRegistry;
import com.homesynapse.device.EntityRole;
import com.homesynapse.device.EntityType;
import com.homesynapse.event.AvailabilityChangedEvent;
import com.homesynapse.event.CausalContext;
import com.homesynapse.event.CommandIdempotency;
import com.homesynapse.event.CommandIssuedEvent;
import com.homesynapse.event.DomainEvent;
import com.homesynapse.event.EventCategory;
import com.homesynapse.event.EventDraft;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventId;
import com.homesynapse.event.EventOrigin;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventPublisher;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.SequenceConflictException;
import com.homesynapse.event.StateChangedEvent;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.platform.identity.AreaId;
import com.homesynapse.platform.identity.AutomationId;
import com.homesynapse.platform.identity.DeviceId;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.FloorId;
import com.homesynapse.platform.identity.IntegrationId;
import com.homesynapse.platform.identity.Ulid;
import com.homesynapse.platform.identity.UlidFactory;
import com.homesynapse.state.Availability;
import com.homesynapse.state.EntityState;
import com.homesynapse.state.StateQueryService;
import com.homesynapse.state.StateSnapshot;
import com.homesynapse.value.AttributeValue;
import com.homesynapse.value.StringValue;

/**
 * Shared test fixtures for the automation engine tests: deterministic identity/clock,
 * builders for the device/state aggregates, in-memory registry/query/publisher stubs,
 * and event-envelope builders.
 *
 * <p>Per §4c, all time is injected via {@link #FIXED_CLOCK} ({@code Clock.fixed}); no
 * test code touches wall-clock time.</p>
 */
final class AutomationTestSupport {

    static final Instant FIXED_INSTANT = Instant.parse("2026-01-01T00:00:00Z");
    static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

    private AutomationTestSupport() {
    }

    static Ulid ulid() {
        return UlidFactory.generate(FIXED_CLOCK);
    }

    static MutableClock mutableClock() {
        return new MutableClock(FIXED_INSTANT, ZoneOffset.UTC);
    }

    static MutableClock mutableClock(Instant start, ZoneId zone) {
        return new MutableClock(start, zone);
    }

    /**
     * A test {@link Clock} whose instant is set explicitly — never wall-clock
     * (NO_DIRECT_TIME_ACCESS-safe). {@link #advance(Duration)} steps it so duration-timer
     * expiry can be driven deterministically.
     */
    static final class MutableClock extends Clock {
        private volatile Instant now;
        private final ZoneId zone;

        MutableClock(Instant start, ZoneId zone) {
            this.now = start;
            this.zone = zone;
        }

        void advance(Duration delta) {
            now = now.plus(delta);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId newZone) {
            return new MutableClock(now, newZone);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    static EntityId entityId() {
        return EntityId.of(ulid());
    }

    static AutomationId automationId() {
        return AutomationId.of(ulid());
    }

    static EventId eventId() {
        return EventId.of(ulid());
    }

    static AttributeValue str(String value) {
        return new StringValue(value);
    }

    // ---- Device / state aggregates -----------------------------------------

    static Entity entity(EntityId id, String slug, EntityType type, AreaId area,
                         EntityRole role, List<String> labels) {
        return new Entity(id, slug, type, slug, null, 0, area, true,
                labels, List.of(), role, FIXED_INSTANT);
    }

    static Area area(AreaId id, String name) {
        return new Area(id, name, null, FIXED_INSTANT);
    }

    static EntityState state(EntityId id, Availability availability,
                             Map<String, AttributeValue> attributes) {
        return new EntityState(id, attributes, availability, 1L,
                FIXED_INSTANT, FIXED_INSTANT, FIXED_INSTANT, null, false);
    }

    static StateSnapshot snapshot(Map<EntityId, EntityState> states) {
        return new StateSnapshot(states, 1L, FIXED_INSTANT, false, Set.of());
    }

    static StateSnapshot snapshotAt(long viewPosition) {
        return new StateSnapshot(Map.of(), viewPosition, FIXED_INSTANT, false, Set.of());
    }

    // ---- Execution / dispatch fixtures -------------------------------------

    static DeviceId deviceId() {
        return DeviceId.of(ulid());
    }

    static IntegrationId integrationId() {
        return IntegrationId.of(ulid());
    }

    /** An entity bound to {@code deviceId} carrying {@code capability} as a feature-0 instance. */
    static Entity entityWith(EntityId id, DeviceId deviceId, Capability capability) {
        CapabilityInstance instance = new CapabilityInstance(
                capability.capabilityId(), capability.version(), capability.namespace(), 0,
                capability.attributeSchemas(), capability.commandDefinitions(),
                capability.confirmationPolicy());
        return new Entity(id, "ent-" + id, EntityType.LIGHT, "Entity", deviceId, 0, null, true,
                List.of(), List.of(instance), EntityRole.PRIMARY, FIXED_INSTANT);
    }

    static Device device(DeviceId id, IntegrationId integrationId) {
        return new Device(id, "dev-" + id, "Device", "Acme", "Model", null, null, null,
                integrationId, null, null, List.of(), Set.of(), FIXED_INSTANT);
    }

    /** {@link DeviceRegistry} stub resolving {@code findDevice} against a fixed device set. */
    static final class MapDeviceRegistry implements DeviceRegistry {
        private final List<Device> devices;

        MapDeviceRegistry(List<Device> devices) {
            this.devices = List.copyOf(devices);
        }

        @Override
        public Device getDevice(DeviceId deviceId) {
            return findDevice(deviceId).orElseThrow(
                    () -> new IllegalArgumentException("no device: " + deviceId));
        }

        @Override
        public Optional<Device> findDevice(DeviceId deviceId) {
            return devices.stream().filter(d -> d.deviceId().equals(deviceId)).findFirst();
        }

        @Override
        public List<Device> listAllDevices() {
            return devices;
        }

        @Override
        public Device createDevice(Device device) {
            throw new UnsupportedOperationException("not used in tests");
        }

        @Override
        public Device updateDevice(Device device) {
            throw new UnsupportedOperationException("not used in tests");
        }

        @Override
        public void removeDevice(DeviceId deviceId) {
            throw new UnsupportedOperationException("not used in tests");
        }

        @Override
        public Optional<Device> findByHardwareIdentifier(String namespace, String value) {
            throw new UnsupportedOperationException("not used in tests");
        }
    }

    /** A {@link SelectorResolver} backed by an explicit {@code selector -> entities} map. */
    static final class FakeSelectorResolver implements SelectorResolver {
        private final Map<Selector, Set<EntityId>> resolutions = new java.util.HashMap<>();

        FakeSelectorResolver bind(Selector selector, Set<EntityId> entities) {
            resolutions.put(selector, Set.copyOf(entities));
            return this;
        }

        @Override
        public Set<EntityId> resolve(Selector selector) {
            return resolutions.getOrDefault(selector, Set.of());
        }
    }

    /** An {@link AutomationRegistry} backed by an explicit definition set ({@code get} only). */
    static final class MapAutomationRegistry implements AutomationRegistry {
        private final Map<AutomationId, AutomationDefinition> byId = new java.util.HashMap<>();

        MapAutomationRegistry add(AutomationDefinition definition) {
            byId.put(definition.automationId(), definition);
            return this;
        }

        @Override
        public void load(List<AutomationDefinition> definitions) {
            byId.clear();
            for (AutomationDefinition definition : definitions) {
                byId.put(definition.automationId(), definition);
            }
        }

        @Override
        public Optional<AutomationDefinition> get(AutomationId id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public Optional<AutomationDefinition> getBySlug(String slug) {
            return byId.values().stream().filter(d -> d.slug().equals(slug)).findFirst();
        }

        @Override
        public List<AutomationDefinition> getAll() {
            return List.copyOf(byId.values());
        }

        @Override
        public void reload(List<AutomationDefinition> definitions) {
            load(definitions);
        }
    }

    // ---- Event envelopes ----------------------------------------------------

    static EventEnvelope envelope(String eventType, SubjectRef subject, DomainEvent payload) {
        EventId id = eventId();
        return new EventEnvelope(id, eventType, 1, FIXED_INSTANT, null, subject, 1L, 0L,
                EventPriority.NORMAL, EventOrigin.PHYSICAL, List.of(EventCategory.AUTOMATION),
                CausalContext.root(id.value()), null, payload);
    }

    static EventEnvelope stateChanged(EntityId entity, String attribute,
                                      AttributeValue oldValue, AttributeValue newValue) {
        return envelope("state_changed", SubjectRef.entity(entity),
                new StateChangedEvent(attribute, oldValue, newValue, eventId()));
    }

    static EventEnvelope entityAvailabilityChanged(EntityId entity, String previous, String next) {
        return envelope("availability_changed", SubjectRef.entity(entity),
                new AvailabilityChangedEvent(previous, next));
    }

    static EventEnvelope deviceAvailabilityChanged(DeviceId device, String previous, String next) {
        return envelope("availability_changed", SubjectRef.device(device),
                new AvailabilityChangedEvent(previous, next));
    }

    static EventEnvelope automationInvoked(AutomationId automationId, String context) {
        return envelope("automation_invoked", SubjectRef.automation(automationId),
                new com.homesynapse.event.AutomationInvokedEvent(context));
    }

    /**
     * A {@code command_issued} envelope on an entity subject with an explicit causal chain
     * (correlation = the Run's, causation = the triggering event — Doc 07 §3.11.2), so the
     * dispatch subscriber's threading can be asserted. Frozen 5-component payload, parameterless.
     */
    static EventEnvelope commandIssued(EntityId target, String commandType,
                                       Ulid correlationId, Ulid causationId) {
        EventId id = eventId();
        return new EventEnvelope(id, EventTypes.COMMAND_ISSUED, 1, FIXED_INSTANT, null,
                SubjectRef.entity(target), 1L, 0L, EventPriority.NORMAL, EventOrigin.AUTOMATION,
                List.of(EventCategory.AUTOMATION),
                CausalContext.chain(correlationId, causationId), null,
                new CommandIssuedEvent(target.value(), commandType, "{}", 5000,
                        CommandIdempotency.IDEMPOTENT));
    }

    // ---- Stub collaborators -------------------------------------------------

    /** In-memory {@link EntityRegistry} over a fixed entity set (read paths only). */
    static final class StubEntityRegistry implements EntityRegistry {
        private final List<Entity> entities;

        StubEntityRegistry(List<Entity> entities) {
            this.entities = List.copyOf(entities);
        }

        @Override
        public Entity getEntity(EntityId entityId) {
            return findEntity(entityId).orElseThrow(
                    () -> new IllegalArgumentException("no entity: " + entityId));
        }

        @Override
        public Optional<Entity> findEntity(EntityId entityId) {
            return entities.stream().filter(e -> e.entityId().equals(entityId)).findFirst();
        }

        @Override
        public List<Entity> listAllEntities() {
            return entities;
        }

        @Override
        public List<Entity> listEntitiesByDevice(DeviceId deviceId) {
            throw new UnsupportedOperationException("not used in tests");
        }

        @Override
        public Entity createEntity(Entity entity) {
            throw new UnsupportedOperationException("not used in tests");
        }

        @Override
        public Entity updateEntity(Entity entity) {
            throw new UnsupportedOperationException("not used in tests");
        }

        @Override
        public void removeEntity(EntityId entityId) {
            throw new UnsupportedOperationException("not used in tests");
        }

        @Override
        public void enableEntity(EntityId entityId) {
            throw new UnsupportedOperationException("not used in tests");
        }

        @Override
        public void disableEntity(EntityId entityId) {
            throw new UnsupportedOperationException("not used in tests");
        }
    }

    /** In-memory {@link AreaRegistry} over a fixed area set (read paths only). */
    static final class StubAreaRegistry implements AreaRegistry {
        private final List<Area> areas;

        StubAreaRegistry(List<Area> areas) {
            this.areas = List.copyOf(areas);
        }

        @Override
        public Optional<Area> get(AreaId id) {
            return areas.stream().filter(a -> a.id().equals(id)).findFirst();
        }

        @Override
        public Collection<Area> getAll() {
            return areas;
        }

        @Override
        public Collection<Area> getByFloor(FloorId floorId) {
            throw new UnsupportedOperationException("not used in tests");
        }

        @Override
        public Collection<Area> getUnassigned() {
            throw new UnsupportedOperationException("not used in tests");
        }
    }

    /** {@link DeviceRegistry} stub — entities in tests carry their own area, so no device. */
    static final class StubDeviceRegistry implements DeviceRegistry {
        @Override
        public Device getDevice(DeviceId deviceId) {
            throw new UnsupportedOperationException("not used in tests");
        }

        @Override
        public Optional<Device> findDevice(DeviceId deviceId) {
            return Optional.empty();
        }

        @Override
        public List<Device> listAllDevices() {
            return List.of();
        }

        @Override
        public Device createDevice(Device device) {
            throw new UnsupportedOperationException("not used in tests");
        }

        @Override
        public Device updateDevice(Device device) {
            throw new UnsupportedOperationException("not used in tests");
        }

        @Override
        public void removeDevice(DeviceId deviceId) {
            throw new UnsupportedOperationException("not used in tests");
        }

        @Override
        public Optional<Device> findByHardwareIdentifier(String namespace, String value) {
            throw new UnsupportedOperationException("not used in tests");
        }
    }

    /** {@link StateQueryService} stub returning a fixed snapshot. */
    static final class StubStateQueryService implements StateQueryService {
        private volatile StateSnapshot snapshot;

        StubStateQueryService(StateSnapshot snapshot) {
            this.snapshot = snapshot;
        }

        void setSnapshot(StateSnapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override
        public Optional<EntityState> getState(EntityId entityId) {
            return Optional.ofNullable(snapshot.states().get(entityId));
        }

        @Override
        public Map<EntityId, EntityState> getStates(Set<EntityId> entityIds) {
            Map<EntityId, EntityState> result = new LinkedHashMap<>();
            for (EntityId id : entityIds) {
                EntityState state = snapshot.states().get(id);
                if (state != null) {
                    result.put(id, state);
                }
            }
            return Map.copyOf(result);
        }

        @Override
        public StateSnapshot getSnapshot() {
            return snapshot;
        }

        @Override
        public long getViewPosition() {
            return snapshot.viewPosition();
        }

        @Override
        public boolean isReady() {
            return !snapshot.replaying();
        }
    }

    /** {@link EventPublisher} stub recording every published envelope (lock-free, LTD-11). */
    static final class RecordingEventPublisher implements EventPublisher {
        private final List<EventEnvelope> published = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public EventEnvelope publish(EventDraft draft, CausalContext cause)
                throws SequenceConflictException {
            Objects.requireNonNull(cause, "cause");
            EventEnvelope envelope = build(draft, cause);
            published.add(envelope);
            return envelope;
        }

        @Override
        public EventEnvelope publishRoot(EventDraft draft)
                throws SequenceConflictException {
            EventEnvelope envelope = build(draft, CausalContext.root(ulid()));
            published.add(envelope);
            return envelope;
        }

        List<EventEnvelope> published() {
            return List.copyOf(published);
        }

        List<EventEnvelope> ofType(String eventType) {
            List<EventEnvelope> matches = new ArrayList<>();
            for (EventEnvelope envelope : published) {
                if (envelope.eventType().equals(eventType)) {
                    matches.add(envelope);
                }
            }
            return matches;
        }

        int countOfType(String eventType) {
            return ofType(eventType).size();
        }

        private EventEnvelope build(EventDraft draft, CausalContext cause) {
            return new EventEnvelope(eventId(), draft.eventType(), draft.schemaVersion(),
                    FIXED_INSTANT, draft.eventTime(), draft.subjectRef(), 1L, 0L,
                    draft.priority(), draft.origin(), List.of(EventCategory.AUTOMATION),
                    cause, draft.actorRef(), draft.payload());
        }
    }
}
