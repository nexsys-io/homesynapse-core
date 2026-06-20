/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import com.homesynapse.platform.identity.DeviceId;
import com.homesynapse.platform.identity.IntegrationId;
import com.homesynapse.platform.identity.Ulid;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link InMemoryDeviceRegistry} (AB-3 MVP substrate).
 */
@DisplayName("InMemoryDeviceRegistry — MVP substrate")
final class InMemoryDeviceRegistryTest {

    private static final Instant CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    InMemoryDeviceRegistryTest() {
    }

    private static Ulid u(long n) {
        return new Ulid(n, n);
    }

    private static DeviceId deviceId(long n) {
        return DeviceId.of(u(n));
    }

    private static Device device(DeviceId id, Set<HardwareIdentifier> hardwareIds) {
        return new Device(id, "dev-" + id, "Device", "Acme", "Model-X",
                null, null, null, IntegrationId.of(u(999)), null, null,
                List.of(), hardwareIds, CREATED_AT);
    }

    @Test
    @DisplayName("starts empty")
    void startsEmpty() {
        InMemoryDeviceRegistry registry = new InMemoryDeviceRegistry();
        assertThat(registry.listAllDevices()).isEmpty();
    }

    @Test
    @DisplayName("create then get/find round-trips the device")
    void createRoundTrip() {
        InMemoryDeviceRegistry registry = new InMemoryDeviceRegistry();
        DeviceId id = deviceId(1);
        Device device = device(id, Set.of());

        Device created = registry.createDevice(device);

        assertThat(created).isEqualTo(device);
        assertThat(registry.getDevice(id)).isEqualTo(device);
        assertThat(registry.findDevice(id)).contains(device);
        assertThat(registry.listAllDevices()).containsExactly(device);
    }

    @Test
    @DisplayName("findDevice returns empty for a missing id; getDevice throws")
    void missingIdSemantics() {
        InMemoryDeviceRegistry registry = new InMemoryDeviceRegistry();
        DeviceId missing = deviceId(99);

        assertThat(registry.findDevice(missing)).isEmpty();
        assertThatThrownBy(() -> registry.getDevice(missing))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("update replaces the stored device; throws on a missing id")
    void update() {
        InMemoryDeviceRegistry registry = new InMemoryDeviceRegistry();
        DeviceId id = deviceId(1);
        registry.createDevice(device(id, Set.of()));

        Device renamed = new Device(id, "dev-renamed", "Renamed", "Acme", "Model-X",
                null, null, null, IntegrationId.of(u(999)), null, null,
                List.of(), Set.of(), CREATED_AT);
        registry.updateDevice(renamed);

        assertThat(registry.getDevice(id).displayName()).isEqualTo("Renamed");
        assertThatThrownBy(() -> registry.updateDevice(device(deviceId(2), Set.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("remove deletes the device; throws on a missing id")
    void remove() {
        InMemoryDeviceRegistry registry = new InMemoryDeviceRegistry();
        DeviceId id = deviceId(1);
        registry.createDevice(device(id, Set.of()));

        registry.removeDevice(id);

        assertThat(registry.findDevice(id)).isEmpty();
        assertThatThrownBy(() -> registry.removeDevice(id))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("findByHardwareIdentifier matches on (namespace, value); empty when absent")
    void findByHardwareIdentifier() {
        InMemoryDeviceRegistry registry = new InMemoryDeviceRegistry();
        HardwareIdentifier zigbee =
                new HardwareIdentifier("zigbee_ieee", "00:11:22:33:44:55:66:77");
        Device device = device(deviceId(1), Set.of(zigbee));
        registry.createDevice(device);
        registry.createDevice(device(deviceId(2), Set.of()));

        assertThat(registry.findByHardwareIdentifier(
                "zigbee_ieee", "00:11:22:33:44:55:66:77")).contains(device);
        assertThat(registry.findByHardwareIdentifier("zigbee_ieee", "nope")).isEmpty();
        assertThat(registry.findByHardwareIdentifier("other", "00:11:22:33:44:55:66:77"))
                .isEmpty();
    }

    @Test
    @DisplayName("listAllDevices returns an unmodifiable snapshot")
    void listAllIsUnmodifiable() {
        InMemoryDeviceRegistry registry = new InMemoryDeviceRegistry();
        registry.createDevice(device(deviceId(1), Set.of()));

        List<Device> all = registry.listAllDevices();
        assertThatThrownBy(() -> all.add(device(deviceId(2), Set.of())))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("concurrent reads against a populated registry are safe")
    void concurrentReadsAreSafe() {
        InMemoryDeviceRegistry registry = new InMemoryDeviceRegistry();
        for (int i = 1; i <= 100; i++) {
            registry.createDevice(device(deviceId(i), Set.of()));
        }

        IntStream.range(0, 2_000).parallel().forEach(i -> {
            assertThat(registry.listAllDevices()).hasSize(100);
            assertThat(registry.findDevice(deviceId((i % 100) + 1))).isPresent();
        });
    }
}
