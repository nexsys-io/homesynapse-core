/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.homesynapse.event.DeviceRegisteredEvent;
import com.homesynapse.event.DeviceRemovedEvent;
import com.homesynapse.event.EntityRegisteredEvent;
import com.homesynapse.platform.identity.AreaId;
import com.homesynapse.platform.identity.DeviceId;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.IntegrationId;
import com.homesynapse.value.AttributeType;
import com.homesynapse.value.BooleanValue;
import com.homesynapse.value.IntValue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link RegistryProjection} + {@link RegistryEventMapper} — the
 * REG-INV-1 single apply path: upsert in log order (DP-8), tombstone handling
 * (DP-7), and the full-fidelity payload mapping (AMD-99 §3).
 */
@DisplayName("RegistryProjection")
class RegistryProjectionTest {

    private static final Instant CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final DeviceId DEVICE_ID =
            DeviceId.parse("01JAAAAAAAAAAAAAAAAAAAAAD1");
    private static final EntityId ENTITY_ID =
            EntityId.parse("01JAAAAAAAAAAAAAAAAAAAAAE1");
    private static final EntityId SECOND_ENTITY_ID =
            EntityId.parse("01JAAAAAAAAAAAAAAAAAAAAAE2");
    private static final IntegrationId INTEGRATION_ID =
            IntegrationId.parse("01JAAAAAAAAAAAAAAAAAAAAAD2");
    private static final AreaId AREA_ID =
            AreaId.parse("01JAAAAAAAAAAAAAAAAAAAAAF1");
    private static final DeviceId VIA_DEVICE_ID =
            DeviceId.parse("01JAAAAAAAAAAAAAAAAAAAAAD3");

    private CountingDeviceRegistry deviceRegistry;
    private CountingEntityRegistry entityRegistry;
    private RegistryProjection projection;

    @BeforeEach
    void setUp() {
        deviceRegistry = new CountingDeviceRegistry(new InMemoryDeviceRegistry());
        entityRegistry = new CountingEntityRegistry(new InMemoryEntityRegistry());
        projection = new RegistryProjection(deviceRegistry, entityRegistry);
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    private static CapabilityInstance onOff(long confirmationTimeoutMs) {
        AttributeSchema on = new AttributeSchema(
                "on", AttributeType.BOOLEAN, null, null, null, null, null, null,
                Set.of(Permission.READ, Permission.NOTIFY), false, true);
        CommandDefinition turnOn = new CommandDefinition(
                "turn_on",
                List.of(new ParameterSchema(
                        "transition_s", AttributeType.INT, 0, 300, false, 0, null)),
                0,
                List.of(new ExpectedOutcome(
                        "on", new ExactMatch(new BooleanValue(true)), 5000L)),
                Duration.ofSeconds(5),
                IdempotencyClass.IDEMPOTENT);
        return new CapabilityInstance(
                "on_off", 1, "core", 0,
                Map.of("on", on),
                Map.of("turn_on", turnOn),
                new ConfirmationPolicy(
                        ConfirmationMode.EXACT_MATCH, List.of("on"), null,
                        confirmationTimeoutMs));
    }

    private static CapabilityInstance brightness() {
        AttributeSchema level = new AttributeSchema(
                "brightness", AttributeType.INT, 0, 254, 1, null, null, null,
                Set.of(Permission.READ, Permission.WRITE), false, true);
        CommandDefinition set = new CommandDefinition(
                "set_brightness",
                List.of(new ParameterSchema(
                        "level", AttributeType.INT, 0, 100, true, 0, null)),
                0,
                List.of(
                        new ExpectedOutcome(
                                "brightness", new WithinTolerance(127.0d, 2.0d), 30000L),
                        new ExpectedOutcome(
                                "brightness", new AnyChange(new IntValue(3)), 30000L),
                        new ExpectedOutcome(
                                "mode", new EnumTransition("dimming"), 30000L)),
                Duration.ofSeconds(30),
                IdempotencyClass.IDEMPOTENT);
        return new CapabilityInstance(
                "brightness", 1, "core", 0,
                Map.of("brightness", level),
                Map.of("set_brightness", set),
                new ConfirmationPolicy(
                        ConfirmationMode.TOLERANCE, List.of("brightness"), 2, 30000L));
    }

    private static Device device() {
        return new Device(
                DEVICE_ID,
                "zigbee-0011223344556677",
                "Signify Hue Bulb",
                "Signify",
                "LWA021",
                null,
                null,
                null,
                INTEGRATION_ID,
                null,
                null,
                List.of("hero"),
                Set.of(new HardwareIdentifier("zigbee", "0011223344556677")),
                CREATED_AT);
    }

    private static Entity entity(EntityId id, int endpoint,
            List<CapabilityInstance> capabilities) {
        return new Entity(
                id,
                "zigbee-0011223344556677-ep" + endpoint,
                EntityType.LIGHT,
                "Signify Hue Bulb",
                DEVICE_ID,
                endpoint,
                null,
                true,
                List.of(),
                capabilities,
                EntityRole.PRIMARY,
                CREATED_AT);
    }

    // ── Apply-creates ───────────────────────────────────────────────────────

    @Test
    @DisplayName("applyDeviceRegistered + applyEntityRegistered create resolvable registry rows")
    void applyCreates() {
        projection.applyDeviceRegistered(RegistryEventMapper.toPayload(device()));
        projection.applyEntityRegistered(RegistryEventMapper.toPayload(
                entity(ENTITY_ID, 1, List.of(onOff(5000L), brightness()))));

        assertThat(deviceRegistry.findDevice(DEVICE_ID)).isPresent();
        assertThat(deviceRegistry.findByHardwareIdentifier("zigbee", "0011223344556677"))
                .isPresent()
                .get()
                .extracting(Device::deviceId)
                .isEqualTo(DEVICE_ID);
        assertThat(entityRegistry.findEntity(ENTITY_ID)).isPresent();
        assertThat(entityRegistry.listEntitiesByDevice(DEVICE_ID)).hasSize(1);
        assertThat(entityRegistry.getEntity(ENTITY_ID).capabilities()).hasSize(2);
    }

    // ── Idempotent self-delivery (DP-8) ─────────────────────────────────────

    @Test
    @DisplayName("applying the same payload twice is a no-op — live self-delivery (DP-8)")
    void idempotentSelfDelivery() {
        DeviceRegisteredEvent devicePayload = RegistryEventMapper.toPayload(device());
        EntityRegisteredEvent entityPayload = RegistryEventMapper.toPayload(
                entity(ENTITY_ID, 1, List.of(onOff(5000L))));

        projection.applyDeviceRegistered(devicePayload);
        projection.applyEntityRegistered(entityPayload);
        int deviceWrites = deviceRegistry.writes;
        int entityWrites = entityRegistry.writes;

        projection.applyDeviceRegistered(devicePayload);
        projection.applyEntityRegistered(entityPayload);

        assertThat(deviceRegistry.writes)
                .as("second device apply must not write (equal-state short-circuit)")
                .isEqualTo(deviceWrites);
        assertThat(entityRegistry.writes)
                .as("second entity apply must not write (equal-state short-circuit)")
                .isEqualTo(entityWrites);
        assertThat(deviceRegistry.listAllDevices()).hasSize(1);
        assertThat(entityRegistry.listAllEntities()).hasSize(1);
    }

    // ── Re-emit update (F1) ─────────────────────────────────────────────────

    @Test
    @DisplayName("a re-emit with changed capabilities replaces the entity (F1 update)")
    void reEmitUpdates() {
        projection.applyDeviceRegistered(RegistryEventMapper.toPayload(device()));
        projection.applyEntityRegistered(RegistryEventMapper.toPayload(
                entity(ENTITY_ID, 1, List.of(onOff(5000L)))));

        projection.applyEntityRegistered(RegistryEventMapper.toPayload(
                entity(ENTITY_ID, 1, List.of(onOff(15000L)))));

        assertThat(entityRegistry.listAllEntities()).hasSize(1);
        assertThat(entityRegistry.getEntity(ENTITY_ID).capabilities().get(0)
                .confirmation().defaultTimeoutMs()).isEqualTo(15000L);
    }

    // ── Tombstone (DP-7 / C4) ───────────────────────────────────────────────

    @Test
    @DisplayName("device_removed removes the device and ALL its entities; absent device is a no-op")
    void tombstone() {
        projection.applyDeviceRegistered(RegistryEventMapper.toPayload(device()));
        projection.applyEntityRegistered(RegistryEventMapper.toPayload(
                entity(ENTITY_ID, 1, List.of(onOff(5000L)))));
        projection.applyEntityRegistered(RegistryEventMapper.toPayload(
                entity(SECOND_ENTITY_ID, 2, List.of(brightness()))));

        projection.applyDeviceRemoved(DEVICE_ID, new DeviceRemovedEvent("user_removed"));

        assertThat(deviceRegistry.findDevice(DEVICE_ID)).isEmpty();
        assertThat(entityRegistry.findEntity(ENTITY_ID)).isEmpty();
        assertThat(entityRegistry.findEntity(SECOND_ENTITY_ID)).isEmpty();
        assertThat(entityRegistry.listAllEntities()).isEmpty();

        assertThatCode(() -> projection.applyDeviceRemoved(
                DEVICE_ID, new DeviceRemovedEvent("user_removed")))
                .as("removing an absent device is a no-op (DP-7)")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("device_removed then re-registration resurrects cleanly (DP-8 by construction)")
    void tombstoneThenReRegister() {
        DeviceRegisteredEvent payload = RegistryEventMapper.toPayload(device());
        projection.applyDeviceRegistered(payload);
        projection.applyDeviceRemoved(DEVICE_ID, new DeviceRemovedEvent("re-adopt"));

        projection.applyDeviceRegistered(payload);

        assertThat(deviceRegistry.findDevice(DEVICE_ID)).isPresent();
    }

    // ── Mapper full-fidelity round-trip ─────────────────────────────────────
    //
    // Fixtures here populate EVERY nullable component with a DISTINCT value —
    // a silent drop (a component mapped to null) or a same-typed adjacent-field
    // swap (serialNumber/firmwareVersion/hardwareVersion are all String) in
    // RegistryEventMapper must fail these two tests, which are the only
    // non-circular domain->payload->domain comparisons in the suite (the
    // AMD-99 §3 no-silent-drops STOP gate).

    @Test
    @DisplayName("Device -> payload -> Device round-trips equal — every nullable populated")
    void deviceRoundTrip() {
        Device original = new Device(
                DEVICE_ID,
                "zigbee-0011223344556677",
                "Hero Bulb",
                "Signify",
                "LWA021",
                "SN-0001",
                "FW-1.108.7",
                "HW-2",
                INTEGRATION_ID,
                AREA_ID,
                VIA_DEVICE_ID,
                List.of("hero", "living-room"),
                Set.of(new HardwareIdentifier("zigbee", "0011223344556677"),
                        new HardwareIdentifier("ble", "AA:BB:CC")),
                CREATED_AT);

        Device rebuilt = RegistryEventMapper.toDevice(
                RegistryEventMapper.toPayload(original));

        assertThat(rebuilt).isEqualTo(original);
        assertThat(rebuilt.serialNumber()).isEqualTo("SN-0001");
        assertThat(rebuilt.firmwareVersion()).isEqualTo("FW-1.108.7");
        assertThat(rebuilt.hardwareVersion()).isEqualTo("HW-2");
        assertThat(rebuilt.areaId()).isEqualTo(AREA_ID);
        assertThat(rebuilt.viaDeviceId()).isEqualTo(VIA_DEVICE_ID);
    }

    @Test
    @DisplayName("Entity -> payload -> Entity round-trips equal, tuning included — every nullable populated")
    void entityRoundTrip() {
        // Canonical-Number fixture: Integer bounds, Integer tolerance, doubles
        // in WithinTolerance — the mirror boundary canonicalizes Number types
        // (documented in PayloadMirrors), so the domain fixture uses the
        // canonical pair and equality holds end-to-end. Non-default scalars
        // (enabled=false, DIAGNOSTIC role, non-empty labels, areaId set) pin
        // the components a default-shaped fixture would cover vacuously.
        Entity original = new Entity(
                ENTITY_ID,
                "zigbee-0011223344556677-ep1",
                EntityType.LIGHT,
                "Hero Bulb",
                DEVICE_ID,
                1,
                AREA_ID,
                false,
                List.of("hero"),
                List.of(onOff(5000L), brightness()),
                EntityRole.DIAGNOSTIC,
                CREATED_AT);

        Entity rebuilt = RegistryEventMapper.toEntity(
                RegistryEventMapper.toPayload(original));

        assertThat(rebuilt).isEqualTo(original);
        assertThat(rebuilt.areaId()).isEqualTo(AREA_ID);
        assertThat(rebuilt.enabled()).isFalse();
        assertThat(rebuilt.entityRole()).isEqualTo(EntityRole.DIAGNOSTIC);
        assertThat(rebuilt.capabilities().get(1).confirmation())
                .isEqualTo(new ConfirmationPolicy(
                        ConfirmationMode.TOLERANCE, List.of("brightness"), 2, 30000L));
    }

    @Test
    @DisplayName("an unknown enum name from a future log fails loudly at apply (never coerced)")
    void unknownEnumNameFailsLoudly() {
        EntityRegisteredEvent payload = RegistryEventMapper.toPayload(
                entity(ENTITY_ID, 1, List.of(onOff(5000L))));
        EntityRegisteredEvent futureType = new EntityRegisteredEvent(
                payload.entityId(), payload.entitySlug(), "HOLOGRAM_PROJECTOR",
                payload.displayName(), payload.deviceId(), payload.endpointIndex(),
                payload.areaId(), payload.enabled(), payload.labels(),
                payload.entityRole(), payload.createdAt(), payload.capabilities());

        assertThatThrownBy(() -> projection.applyEntityRegistered(futureType))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a helper entity (null deviceId) is rejected at emission mapping")
    void helperEntityRejectedAtMapping() {
        Entity helper = new Entity(
                ENTITY_ID, "helper", EntityType.SENSOR, "Helper", null, 0, null,
                true, List.of(), List.of(), EntityRole.PRIMARY, CREATED_AT);

        assertThatThrownBy(() -> RegistryEventMapper.toPayload(helper))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("deviceId");
    }

    // ── Counting decorators (test fixtures — free to touch registries) ──────

    private static final class CountingDeviceRegistry implements DeviceRegistry {
        private final DeviceRegistry delegate;
        private int writes;

        private CountingDeviceRegistry(DeviceRegistry delegate) {
            this.delegate = delegate;
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
            writes++;
            return delegate.createDevice(device);
        }

        @Override
        public Device updateDevice(Device device) {
            writes++;
            return delegate.updateDevice(device);
        }

        @Override
        public void removeDevice(DeviceId deviceId) {
            writes++;
            delegate.removeDevice(deviceId);
        }

        @Override
        public Optional<Device> findByHardwareIdentifier(String namespace, String value) {
            return delegate.findByHardwareIdentifier(namespace, value);
        }
    }

    private static final class CountingEntityRegistry implements EntityRegistry {
        private final EntityRegistry delegate;
        private int writes;

        private CountingEntityRegistry(EntityRegistry delegate) {
            this.delegate = delegate;
        }

        @Override
        public Entity getEntity(EntityId entityId) {
            return delegate.getEntity(entityId);
        }

        @Override
        public Optional<Entity> findEntity(EntityId entityId) {
            return delegate.findEntity(entityId);
        }

        @Override
        public List<Entity> listAllEntities() {
            return delegate.listAllEntities();
        }

        @Override
        public List<Entity> listEntitiesByDevice(DeviceId deviceId) {
            return delegate.listEntitiesByDevice(deviceId);
        }

        @Override
        public Entity createEntity(Entity entity) {
            writes++;
            return delegate.createEntity(entity);
        }

        @Override
        public Entity updateEntity(Entity entity) {
            writes++;
            return delegate.updateEntity(entity);
        }

        @Override
        public void removeEntity(EntityId entityId) {
            writes++;
            delegate.removeEntity(entityId);
        }

        @Override
        public void enableEntity(EntityId entityId) {
            writes++;
            delegate.enableEntity(entityId);
        }

        @Override
        public void disableEntity(EntityId entityId) {
            writes++;
            delegate.disableEntity(entityId);
        }
    }
}
