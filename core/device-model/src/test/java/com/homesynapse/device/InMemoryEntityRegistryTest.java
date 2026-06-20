/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import com.homesynapse.platform.identity.DeviceId;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.Ulid;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link InMemoryEntityRegistry} (AB-3 MVP substrate).
 *
 * <p>No {@link java.time.Clock} is injected because the registry uses no time
 * source; entity timestamps are fixed literals (NO_DIRECT_TIME_ACCESS-safe).</p>
 */
@DisplayName("InMemoryEntityRegistry — MVP substrate")
final class InMemoryEntityRegistryTest {

    private static final Instant CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    InMemoryEntityRegistryTest() {
    }

    private static Ulid u(long n) {
        return new Ulid(n, n);
    }

    private static EntityId entityId(long n) {
        return EntityId.of(u(n));
    }

    private static DeviceId deviceId(long n) {
        return DeviceId.of(u(n));
    }

    private static Entity entity(EntityId id, DeviceId deviceId, boolean enabled) {
        return new Entity(id, "slug-" + id, EntityType.LIGHT, "Light",
                deviceId, 0, null, enabled, List.of(), List.of(), CREATED_AT);
    }

    @Test
    @DisplayName("starts empty (zero-configuration first boot)")
    void startsEmpty() {
        InMemoryEntityRegistry registry = new InMemoryEntityRegistry();
        assertThat(registry.listAllEntities()).isEmpty();
    }

    @Test
    @DisplayName("create then get/find round-trips the entity")
    void createRoundTrip() {
        InMemoryEntityRegistry registry = new InMemoryEntityRegistry();
        EntityId id = entityId(1);
        Entity entity = entity(id, deviceId(10), true);

        Entity created = registry.createEntity(entity);

        assertThat(created).isEqualTo(entity);
        assertThat(registry.getEntity(id)).isEqualTo(entity);
        assertThat(registry.findEntity(id)).contains(entity);
        assertThat(registry.listAllEntities()).containsExactly(entity);
    }

    @Test
    @DisplayName("findEntity returns empty for a missing id; getEntity throws")
    void missingIdSemantics() {
        InMemoryEntityRegistry registry = new InMemoryEntityRegistry();
        EntityId missing = entityId(99);

        assertThat(registry.findEntity(missing)).isEmpty();
        assertThatThrownBy(() -> registry.getEntity(missing))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("update replaces the stored entity; throws on a missing id")
    void update() {
        InMemoryEntityRegistry registry = new InMemoryEntityRegistry();
        EntityId id = entityId(1);
        registry.createEntity(entity(id, deviceId(10), true));

        Entity renamed = new Entity(id, "slug-renamed", EntityType.LIGHT, "Renamed",
                deviceId(10), 0, null, true, List.of(), List.of(), CREATED_AT);
        registry.updateEntity(renamed);

        assertThat(registry.getEntity(id).displayName()).isEqualTo("Renamed");
        assertThatThrownBy(() -> registry.updateEntity(entity(entityId(2), null, true)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("remove deletes the entity; throws on a missing id")
    void remove() {
        InMemoryEntityRegistry registry = new InMemoryEntityRegistry();
        EntityId id = entityId(1);
        registry.createEntity(entity(id, deviceId(10), true));

        registry.removeEntity(id);

        assertThat(registry.findEntity(id)).isEmpty();
        assertThatThrownBy(() -> registry.removeEntity(id))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("enable/disable toggle the entity's enabled flag; throw on a missing id")
    void enableDisable() {
        InMemoryEntityRegistry registry = new InMemoryEntityRegistry();
        EntityId id = entityId(1);
        registry.createEntity(entity(id, deviceId(10), true));

        registry.disableEntity(id);
        assertThat(registry.getEntity(id).enabled()).isFalse();

        registry.enableEntity(id);
        assertThat(registry.getEntity(id).enabled()).isTrue();

        assertThatThrownBy(() -> registry.enableEntity(entityId(2)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.disableEntity(entityId(2)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("listEntitiesByDevice filters on owning device; null-device helpers excluded")
    void listEntitiesByDevice() {
        InMemoryEntityRegistry registry = new InMemoryEntityRegistry();
        DeviceId deviceA = deviceId(10);
        DeviceId deviceB = deviceId(20);
        Entity a1 = entity(entityId(1), deviceA, true);
        Entity a2 = entity(entityId(2), deviceA, true);
        Entity b1 = entity(entityId(3), deviceB, true);
        Entity helper = entity(entityId(4), null, true); // no owning device
        registry.createEntity(a1);
        registry.createEntity(a2);
        registry.createEntity(b1);
        registry.createEntity(helper);

        assertThat(registry.listEntitiesByDevice(deviceA)).containsExactlyInAnyOrder(a1, a2);
        assertThat(registry.listEntitiesByDevice(deviceB)).containsExactly(b1);
        assertThat(registry.listEntitiesByDevice(deviceId(30))).isEmpty();
    }

    @Test
    @DisplayName("listAllEntities returns an unmodifiable snapshot")
    void listAllIsUnmodifiable() {
        InMemoryEntityRegistry registry = new InMemoryEntityRegistry();
        registry.createEntity(entity(entityId(1), deviceId(10), true));

        List<Entity> all = registry.listAllEntities();
        assertThatThrownBy(() -> all.add(entity(entityId(2), null, true)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("concurrent reads against a populated registry are safe")
    void concurrentReadsAreSafe() {
        InMemoryEntityRegistry registry = new InMemoryEntityRegistry();
        for (int i = 1; i <= 100; i++) {
            registry.createEntity(entity(entityId(i), deviceId(1000 + i), true));
        }

        IntStream.range(0, 2_000).parallel().forEach(i -> {
            assertThat(registry.listAllEntities()).hasSize(100);
            assertThat(registry.findEntity(entityId((i % 100) + 1))).isPresent();
        });
    }
}
