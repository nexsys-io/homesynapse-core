/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import com.homesynapse.platform.identity.AreaId;
import com.homesynapse.platform.identity.FloorId;
import com.homesynapse.platform.identity.Ulid;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link InMemoryAreaRegistry} (AB-3 MVP substrate, read-only).
 */
@DisplayName("InMemoryAreaRegistry — MVP substrate (read-only)")
final class InMemoryAreaRegistryTest {

    private static final Instant CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    InMemoryAreaRegistryTest() {
    }

    private static Ulid u(long n) {
        return new Ulid(n, n);
    }

    private static AreaId areaId(long n) {
        return AreaId.of(u(n));
    }

    private static FloorId floorId(long n) {
        return FloorId.of(u(n));
    }

    private static Area area(AreaId id, String name, FloorId floor) {
        return new Area(id, name, floor, CREATED_AT);
    }

    @Test
    @DisplayName("default constructor starts empty")
    void startsEmpty() {
        InMemoryAreaRegistry registry = new InMemoryAreaRegistry();
        assertThat(registry.getAll()).isEmpty();
        assertThat(registry.get(areaId(1))).isEmpty();
        assertThat(registry.getUnassigned()).isEmpty();
        assertThat(registry.getByFloor(floorId(1))).isEmpty();
    }

    @Test
    @DisplayName("get returns the seeded area; empty for a missing id (no throw)")
    void getSemantics() {
        Area living = area(areaId(1), "Living Room", floorId(100));
        InMemoryAreaRegistry registry = new InMemoryAreaRegistry(List.of(living));

        assertThat(registry.get(areaId(1))).contains(living);
        assertThat(registry.get(areaId(2))).isEmpty();
    }

    @Test
    @DisplayName("getByFloor filters on floorId; getUnassigned returns null-floor areas")
    void floorFiltering() {
        FloorId ground = floorId(100);
        FloorId upstairs = floorId(200);
        Area living = area(areaId(1), "Living Room", ground);
        Area kitchen = area(areaId(2), "Kitchen", ground);
        Area bedroom = area(areaId(3), "Bedroom", upstairs);
        Area garage = area(areaId(4), "Garage", null); // unassigned

        InMemoryAreaRegistry registry =
                new InMemoryAreaRegistry(List.of(living, kitchen, bedroom, garage));

        assertThat(registry.getAll())
                .containsExactlyInAnyOrder(living, kitchen, bedroom, garage);
        assertThat(registry.getByFloor(ground)).containsExactlyInAnyOrder(living, kitchen);
        assertThat(registry.getByFloor(upstairs)).containsExactly(bedroom);
        assertThat(registry.getByFloor(floorId(999))).isEmpty();
        assertThat(registry.getUnassigned()).containsExactly(garage);
    }

    @Test
    @DisplayName("getAll returns an unmodifiable snapshot")
    void getAllIsUnmodifiable() {
        InMemoryAreaRegistry registry =
                new InMemoryAreaRegistry(List.of(area(areaId(1), "Living Room", null)));

        Collection<Area> all = registry.getAll();
        assertThatThrownBy(() -> all.add(area(areaId(2), "Kitchen", null)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("concurrent reads against a seeded registry are safe")
    void concurrentReadsAreSafe() {
        FloorId ground = floorId(100);
        java.util.List<Area> seed = new java.util.ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            seed.add(area(areaId(i), "Area " + i, i % 2 == 0 ? ground : null));
        }
        InMemoryAreaRegistry registry = new InMemoryAreaRegistry(seed);

        IntStream.range(0, 2_000).parallel().forEach(i -> {
            assertThat(registry.getAll()).hasSize(100);
            assertThat(registry.getByFloor(ground)).hasSize(50);
            assertThat(registry.getUnassigned()).hasSize(50);
        });
    }
}
