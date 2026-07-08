/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.homesynapse.platform.identity.Ulid;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link DeviceRegisteredEvent} — the AMD-99 full-fidelity device
 * registration payload (REG-INV-1: the log alone reconstructs the registries).
 */
@DisplayName("DeviceRegisteredEvent")
class DeviceRegisteredEventTest {

    private static final Ulid DEVICE_ID = Ulid.parse("01JAAAAAAAAAAAAAAAAAAAAAD1");
    private static final Instant CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");

    private static DeviceRegisteredEvent event(List<HardwareIdentifierRef> identifiers) {
        return new DeviceRegisteredEvent(
                DEVICE_ID,
                "zigbee-0011223344556677",
                "Signify Hue Bulb",
                "Signify",
                "LWA021",
                null,
                null,
                null,
                "01JAAAAAAAAAAAAAAAAAAAAAD2",
                null,
                null,
                List.of("hero"),
                identifiers,
                CREATED_AT);
    }

    @Test
    @DisplayName("carries @EventType(device_registered) and implements DomainEvent")
    void annotatedAndTyped() {
        EventType annotation = DeviceRegisteredEvent.class.getAnnotation(EventType.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).isEqualTo(EventTypes.DEVICE_REGISTERED);
        assertThat(DomainEvent.class).isAssignableFrom(DeviceRegisteredEvent.class);
    }

    @Test
    @DisplayName("hardwareIdentifiers sort by (namespace, value) — deterministic encoding")
    void hardwareIdentifiers_sortDeterministically() {
        var unsorted = new ArrayList<HardwareIdentifierRef>();
        unsorted.add(new HardwareIdentifierRef("zigbee", "BB"));
        unsorted.add(new HardwareIdentifierRef("zigbee", "AA"));
        unsorted.add(new HardwareIdentifierRef("ble", "CC"));

        DeviceRegisteredEvent registered = event(unsorted);

        assertThat(registered.hardwareIdentifiers()).containsExactly(
                new HardwareIdentifierRef("ble", "CC"),
                new HardwareIdentifierRef("zigbee", "AA"),
                new HardwareIdentifierRef("zigbee", "BB"));
    }

    @Test
    @DisplayName("collection components are defensively copied and unmodifiable")
    void collectionsUnmodifiable() {
        DeviceRegisteredEvent registered =
                event(List.of(new HardwareIdentifierRef("zigbee", "AA")));

        assertThatThrownBy(() -> registered.labels().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> registered.hardwareIdentifiers().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("nullable metadata components pass through as null")
    void nullableComponents() {
        DeviceRegisteredEvent registered =
                event(List.of(new HardwareIdentifierRef("zigbee", "AA")));

        assertThat(registered.serialNumber()).isNull();
        assertThat(registered.firmwareVersion()).isNull();
        assertThat(registered.hardwareVersion()).isNull();
        assertThat(registered.areaId()).isNull();
        assertThat(registered.viaDeviceId()).isNull();
    }

    @Test
    @DisplayName("required components reject null")
    void requiredComponentsRejectNull() {
        assertThatThrownBy(() -> new DeviceRegisteredEvent(
                null, "slug", "name", "mfr", "model", null, null, null,
                "01JAAAAAAAAAAAAAAAAAAAAAD2", null, null, List.of(), List.of(), CREATED_AT))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("deviceId");
        assertThatThrownBy(() -> new DeviceRegisteredEvent(
                DEVICE_ID, "slug", "name", "mfr", "model", null, null, null,
                null, null, null, List.of(), List.of(), CREATED_AT))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("integrationId");
        assertThatThrownBy(() -> new DeviceRegisteredEvent(
                DEVICE_ID, "slug", "name", "mfr", "model", null, null, null,
                "01JAAAAAAAAAAAAAAAAAAAAAD2", null, null, List.of(), null, CREATED_AT))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DeviceRegisteredEvent(
                DEVICE_ID, "slug", "name", "mfr", "model", null, null, null,
                "01JAAAAAAAAAAAAAAAAAAAAAD2", null, null, List.of(), List.of(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("createdAt");
    }

    @Test
    @DisplayName("HardwareIdentifierRef rejects null components")
    void hardwareIdentifierRef_rejectsNull() {
        assertThatThrownBy(() -> new HardwareIdentifierRef(null, "AA"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new HardwareIdentifierRef("zigbee", null))
                .isInstanceOf(NullPointerException.class);
    }
}
