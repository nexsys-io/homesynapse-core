/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.homesynapse.device.CapabilityInstance;
import com.homesynapse.device.Entity;
import com.homesynapse.device.EntityRegistry;
import com.homesynapse.device.EntityType;
import com.homesynapse.device.InMemoryEntityRegistry;
import com.homesynapse.device.StandardCapabilities;
import com.homesynapse.event.bus.SubscriberMode;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.Ulid;
import com.homesynapse.value.AttributeValue;
import com.homesynapse.value.DegradedAttributeValue;
import com.homesynapse.value.IntValue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * M9.4b §2.3 — the query-time {@code brightness_percent} decoration
 * (Doc 08 §3.5: "percentage derived at query time"). The materialized
 * {@code brightness} attribute is the CANONICAL 0-254 level; every read path
 * ({@code getState}/{@code getStates}/{@code getSnapshot}) appends the derived
 * {@code brightness_percent} key when — and only when — the entity's brightness
 * {@link com.homesynapse.device.AttributeSchema} carries numeric bounds and the
 * materialized value is numeric. Derived at read, NEVER stored, NEVER an event.
 * Every miss (registry absent, entity unknown, schema boundless, value
 * null/degraded/non-numeric) yields the undecorated result — the read path
 * never throws.
 */
@DisplayName("MaterializedStateQueryService — brightness_percent decoration (M9.4b §2.3)")
class MaterializedStateQueryServicePercentTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-07-04T12:00:00Z"), ZoneOffset.UTC);
    private static final Instant T0 = FIXED_CLOCK.instant();

    private static final EntityId LIGHT = new EntityId(new Ulid(0x1L, 0x1L));
    private static final EntityId SENSOR = new EntityId(new Ulid(0x2L, 0x2L));

    private InMemoryStateStore stateStore;
    private InMemoryEntityRegistry entityRegistry;
    private MaterializedStateQueryService service;

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    MaterializedStateQueryServicePercentTest() {
    }

    @BeforeEach
    void setUp() {
        stateStore = new InMemoryStateStore();
        entityRegistry = new InMemoryEntityRegistry();
        service = new MaterializedStateQueryService(
                stateStore,
                () -> SubscriberMode.LIVE,
                () -> 42L,
                () -> entityRegistry,
                FIXED_CLOCK);
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private void registerLightWithBrightness(EntityId id) {
        CapabilityInstance brightness = instanceOf(StandardCapabilities.brightness());
        CapabilityInstance onOff = instanceOf(StandardCapabilities.onOff());
        entityRegistry.createEntity(new Entity(id, "light-" + id, EntityType.LIGHT,
                "Light", null, 0, null, true, List.of(), List.of(onOff, brightness), T0));
    }

    private static CapabilityInstance instanceOf(com.homesynapse.device.Capability cap) {
        return new CapabilityInstance(cap.capabilityId(), cap.version(), cap.namespace(), 0,
                cap.attributeSchemas(), cap.commandDefinitions(), cap.confirmationPolicy());
    }

    private void store(EntityId id, Map<String, AttributeValue> attributes) {
        HashMap<String, AttributeValue> map = new HashMap<>(attributes);
        stateStore.put(id, new EntityState(id, java.util.Collections.unmodifiableMap(map),
                Availability.AVAILABLE, 1L, T0, T0, T0, null, false));
    }

    // ── the derivation table ────────────────────────────────────────────────

    @Test
    @DisplayName("level 0 → 0 %, 127 → 50 %, 254 → 100 % (round to nearest)")
    void levelToPercent_table() {
        registerLightWithBrightness(LIGHT);

        store(LIGHT, Map.of("brightness", new IntValue(0)));
        assertThat(percentOf(LIGHT)).isEqualTo(new IntValue(0));

        store(LIGHT, Map.of("brightness", new IntValue(127)));
        assertThat(percentOf(LIGHT)).isEqualTo(new IntValue(50));

        store(LIGHT, Map.of("brightness", new IntValue(254)));
        assertThat(percentOf(LIGHT)).isEqualTo(new IntValue(100));
    }

    @Test
    @DisplayName("the canonical level value is retained beside the derived percent")
    void canonicalLevelRetained() {
        registerLightWithBrightness(LIGHT);
        store(LIGHT, Map.of("brightness", new IntValue(127), "on",
                new com.homesynapse.value.BooleanValue(true)));

        Map<String, AttributeValue> attributes =
                service.getState(LIGHT).orElseThrow().attributes();

        assertThat(attributes.get("brightness")).isEqualTo(new IntValue(127));
        assertThat(attributes.get("brightness_percent")).isEqualTo(new IntValue(50));
        assertThat(attributes.get("on"))
                .isEqualTo(new com.homesynapse.value.BooleanValue(true));
    }

    @Test
    @DisplayName("the decorated attributes map stays unmodifiable")
    void decoratedMapIsUnmodifiable() {
        registerLightWithBrightness(LIGHT);
        store(LIGHT, Map.of("brightness", new IntValue(127)));

        Map<String, AttributeValue> attributes =
                service.getState(LIGHT).orElseThrow().attributes();

        assertThatThrownBy(() -> attributes.put("injected", new IntValue(1)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ── the no-decoration misses (never throw) ──────────────────────────────

    @Test
    @DisplayName("an entity the registry does not know is undecorated")
    void unknownEntity_undecorated() {
        store(LIGHT, Map.of("brightness", new IntValue(127)));

        assertThat(service.getState(LIGHT).orElseThrow().attributes())
                .doesNotContainKey("brightness_percent");
    }

    @Test
    @DisplayName("an entity with no brightness schema is undecorated")
    void noBrightnessSchema_undecorated() {
        entityRegistry.createEntity(new Entity(SENSOR, "sensor", EntityType.BINARY_SENSOR,
                "Sensor", null, 0, null, true, List.of(),
                List.of(instanceOf(StandardCapabilities.occupancy())), T0));
        store(SENSOR, Map.of("brightness", new IntValue(127)));

        assertThat(service.getState(SENSOR).orElseThrow().attributes())
                .doesNotContainKey("brightness_percent");
    }

    @Test
    @DisplayName("a degraded brightness value is undecorated")
    void degradedValue_undecorated() {
        registerLightWithBrightness(LIGHT);
        store(LIGHT, Map.of("brightness",
                new DegradedAttributeValue("IntValue", "banana", "not parseable")));

        assertThat(service.getState(LIGHT).orElseThrow().attributes())
                .doesNotContainKey("brightness_percent");
    }

    @Test
    @DisplayName("a null brightness value (never reported) is undecorated — and never throws")
    void nullValue_undecorated() {
        registerLightWithBrightness(LIGHT);
        HashMap<String, AttributeValue> attributes = new HashMap<>();
        attributes.put("brightness", null);   // schema-declared, never reported
        store(LIGHT, attributes);

        assertThat(service.getState(LIGHT).orElseThrow().attributes())
                .doesNotContainKey("brightness_percent");
    }

    @Test
    @DisplayName("a null registry supplier value is undecorated (the 4-arg factory path)")
    void nullRegistry_undecorated() {
        MaterializedStateQueryService withoutRegistry = new MaterializedStateQueryService(
                stateStore, () -> SubscriberMode.LIVE, () -> 42L, () -> null, FIXED_CLOCK);
        registerLightWithBrightness(LIGHT);
        store(LIGHT, Map.of("brightness", new IntValue(127)));

        assertThat(withoutRegistry.getState(LIGHT).orElseThrow().attributes())
                .doesNotContainKey("brightness_percent");
    }

    @Test
    @DisplayName("an entity without a brightness attribute is untouched")
    void nonBrightnessEntity_untouched() {
        registerLightWithBrightness(LIGHT);
        Map<String, AttributeValue> original =
                Map.of("on", new com.homesynapse.value.BooleanValue(true));
        store(LIGHT, original);

        assertThat(service.getState(LIGHT).orElseThrow().attributes())
                .containsExactlyInAnyOrderEntriesOf(original);
    }

    @Test
    @DisplayName("a throwing registry read degrades to undecorated — the read path never 500s")
    void throwingRegistry_undecorated() {
        EntityRegistry throwing = new ThrowingRegistry();
        MaterializedStateQueryService overThrowing = new MaterializedStateQueryService(
                stateStore, () -> SubscriberMode.LIVE, () -> 42L, () -> throwing,
                FIXED_CLOCK);
        store(LIGHT, Map.of("brightness", new IntValue(127)));

        assertThat(overThrowing.getState(LIGHT).orElseThrow().attributes())
                .doesNotContainKey("brightness_percent");
    }

    // ── the other read paths ────────────────────────────────────────────────

    @Test
    @DisplayName("getStates decorates each requested entity")
    void getStates_decorated() {
        registerLightWithBrightness(LIGHT);
        store(LIGHT, Map.of("brightness", new IntValue(254)));

        Map<EntityId, EntityState> states = service.getStates(java.util.Set.of(LIGHT));

        assertThat(states.get(LIGHT).attributes().get("brightness_percent"))
                .isEqualTo(new IntValue(100));
    }

    @Test
    @DisplayName("getSnapshot decorates the whole view")
    void snapshot_decorated() {
        registerLightWithBrightness(LIGHT);
        store(LIGHT, Map.of("brightness", new IntValue(127)));
        store(SENSOR, Map.of("occupied", new com.homesynapse.value.BooleanValue(true)));

        StateSnapshot snapshot = service.getSnapshot();

        assertThat(snapshot.states().get(LIGHT).attributes().get("brightness_percent"))
                .isEqualTo(new IntValue(50));
        assertThat(snapshot.states().get(SENSOR).attributes())
                .doesNotContainKey("brightness_percent");
    }

    private AttributeValue percentOf(EntityId id) {
        return service.getState(id).orElseThrow().attributes().get("brightness_percent");
    }

    /** A registry whose reads throw — the decoration must degrade, never propagate. */
    private static final class ThrowingRegistry implements EntityRegistry {
        ThrowingRegistry() {
        }

        @Override
        public Entity getEntity(EntityId entityId) {
            throw new IllegalStateException("registry read failed");
        }

        @Override
        public java.util.Optional<Entity> findEntity(EntityId entityId) {
            throw new IllegalStateException("registry read failed");
        }

        @Override
        public List<Entity> listAllEntities() {
            throw new IllegalStateException("registry read failed");
        }

        @Override
        public List<Entity> listEntitiesByDevice(
                com.homesynapse.platform.identity.DeviceId deviceId) {
            throw new IllegalStateException("registry read failed");
        }

        @Override
        public Entity createEntity(Entity entity) {
            throw new IllegalStateException("registry write failed");
        }

        @Override
        public Entity updateEntity(Entity entity) {
            throw new IllegalStateException("registry write failed");
        }

        @Override
        public void removeEntity(EntityId entityId) {
            throw new IllegalStateException("registry write failed");
        }

        @Override
        public void enableEntity(EntityId entityId) {
            throw new IllegalStateException("registry write failed");
        }

        @Override
        public void disableEntity(EntityId entityId) {
            throw new IllegalStateException("registry write failed");
        }
    }
}
