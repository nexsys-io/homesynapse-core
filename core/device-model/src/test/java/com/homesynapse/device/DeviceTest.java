// HomeSynapse Core / Copyright (c) 2026 NexSys. All rights reserved.
package com.homesynapse.device;

import static org.assertj.core.api.Assertions.assertThat;

import com.homesynapse.platform.identity.AreaId;
import com.homesynapse.platform.identity.DeviceId;
import com.homesynapse.platform.identity.IntegrationId;
import com.homesynapse.platform.identity.Ulid;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Tests for {@link Device} — physical device container record.
 */
@DisplayName("Device")
class DeviceTest {

    private static final DeviceId DEVICE_ID =
            DeviceId.of(new Ulid(0x0192A3B4C5D6E7F0L, 0x0102030405060708L));
    private static final DeviceId VIA_DEVICE_ID =
            DeviceId.of(new Ulid(0x0192A3B4C5D6E7F1L, 0x0102030405060709L));
    private static final IntegrationId INTEGRATION_ID =
            IntegrationId.of(new Ulid(0x0192A3B4C5D6E7F2L, 0x010203040506070AL));
    private static final AreaId AREA_ID =
            AreaId.of(new Ulid(0x0192A3B4C5D6E7F3L, 0x010203040506070BL));
    private static final Instant CREATED_AT = Instant.parse("2026-01-15T12:00:00Z");

    private static Device fullDevice() {
        return new Device(
                DEVICE_ID,
                "kitchen-bulb",
                "Kitchen Bulb",
                "IKEA",
                "TRADFRI-E27",
                "SN-12345",
                "1.4.2",
                "rev-B",
                INTEGRATION_ID,
                AREA_ID,
                VIA_DEVICE_ID,
                List.of("lighting", "kitchen"),
                List.of(new HardwareIdentifier("zigbee_ieee", "00:11:22:33:44:55:66:77")),
                CREATED_AT);
    }

    // -- Construction ---------------------------------------------------------

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {

        @Test
        @DisplayName("all 14 fields accessible after construction")
        void allFieldsAccessible() {
            Device d = fullDevice();

            assertThat(d.deviceId()).isEqualTo(DEVICE_ID);
            assertThat(d.deviceSlug()).isEqualTo("kitchen-bulb");
            assertThat(d.displayName()).isEqualTo("Kitchen Bulb");
            assertThat(d.manufacturer()).isEqualTo("IKEA");
            assertThat(d.model()).isEqualTo("TRADFRI-E27");
            assertThat(d.serialNumber()).isEqualTo("SN-12345");
            assertThat(d.firmwareVersion()).isEqualTo("1.4.2");
            assertThat(d.hardwareVersion()).isEqualTo("rev-B");
            assertThat(d.integrationId()).isEqualTo(INTEGRATION_ID);
            assertThat(d.areaId()).isEqualTo(AREA_ID);
            assertThat(d.viaDeviceId()).isEqualTo(VIA_DEVICE_ID);
            assertThat(d.labels()).containsExactly("lighting", "kitchen");
            assertThat(d.hardwareIdentifiers()).hasSize(1);
            assertThat(d.createdAt()).isEqualTo(CREATED_AT);
        }

        @Test
        @DisplayName("record has exactly 14 components")
        void exactlyFourteenFields() {
            assertThat(Device.class.getRecordComponents()).hasSize(14);
        }
    }

    // -- Nullable fields ------------------------------------------------------

    @Nested
    @DisplayName("Nullable fields")
    class NullableFieldTests {

        @Test
        @DisplayName("serialNumber accepts null")
        void nullSerialNumber() {
            Device d = new Device(DEVICE_ID, "slug", "name", "mfg", "model",
                    null, "1.0", "rev-A", INTEGRATION_ID, AREA_ID,
                    null, List.of(), List.of(), CREATED_AT);
            assertThat(d.serialNumber()).isNull();
        }

        @Test
        @DisplayName("firmwareVersion accepts null")
        void nullFirmwareVersion() {
            Device d = new Device(DEVICE_ID, "slug", "name", "mfg", "model",
                    "SN", null, "rev-A", INTEGRATION_ID, AREA_ID,
                    null, List.of(), List.of(), CREATED_AT);
            assertThat(d.firmwareVersion()).isNull();
        }

        @Test
        @DisplayName("hardwareVersion accepts null")
        void nullHardwareVersion() {
            Device d = new Device(DEVICE_ID, "slug", "name", "mfg", "model",
                    "SN", "1.0", null, INTEGRATION_ID, AREA_ID,
                    null, List.of(), List.of(), CREATED_AT);
            assertThat(d.hardwareVersion()).isNull();
        }

        @Test
        @DisplayName("areaId accepts null for unassigned devices")
        void nullAreaId() {
            Device d = new Device(DEVICE_ID, "slug", "name", "mfg", "model",
                    null, null, null, INTEGRATION_ID, null,
                    null, List.of(), List.of(), CREATED_AT);
            assertThat(d.areaId()).isNull();
        }

        @Test
        @DisplayName("viaDeviceId accepts null for directly connected devices")
        void nullViaDeviceId() {
            Device d = new Device(DEVICE_ID, "slug", "name", "mfg", "model",
                    null, null, null, INTEGRATION_ID, AREA_ID,
                    null, List.of(), List.of(), CREATED_AT);
            assertThat(d.viaDeviceId()).isNull();
        }

        @Test
        @DisplayName("all five nullable fields null simultaneously")
        void allNullableFieldsNull() {
            Device d = new Device(DEVICE_ID, "slug", "name", "mfg", "model",
                    null, null, null, INTEGRATION_ID, null,
                    null, List.of(), List.of(), CREATED_AT);
            assertThat(d.serialNumber()).isNull();
            assertThat(d.firmwareVersion()).isNull();
            assertThat(d.hardwareVersion()).isNull();
            assertThat(d.areaId()).isNull();
            assertThat(d.viaDeviceId()).isNull();
        }
    }

    // -- Collections ----------------------------------------------------------

    @Nested
    @DisplayName("Collection fields")
    class CollectionTests {

        @Test
        @DisplayName("labels list is preserved")
        void labelsPreserved() {
            Device d = fullDevice();
            assertThat(d.labels()).containsExactly("lighting", "kitchen");
        }

        @Test
        @DisplayName("hardwareIdentifiers list is preserved")
        void hardwareIdentifiersPreserved() {
            Device d = fullDevice();
            assertThat(d.hardwareIdentifiers()).hasSize(1);
            assertThat(d.hardwareIdentifiers().get(0).namespace()).isEqualTo("zigbee_ieee");
        }

        @Test
        @DisplayName("empty collections are accepted")
        void emptyCollections() {
            Device d = new Device(DEVICE_ID, "slug", "name", "mfg", "model",
                    null, null, null, INTEGRATION_ID, null,
                    null, List.of(), List.of(), CREATED_AT);
            assertThat(d.labels()).isEmpty();
            assertThat(d.hardwareIdentifiers()).isEmpty();
        }
    }

    // -- Equals / hashCode ----------------------------------------------------

    @Nested
    @DisplayName("Equals and hashCode")
    class EqualsHashCodeTests {

        @Test
        @DisplayName("identical Devices are equal")
        void identicalEqual() {
            Device a = fullDevice();
            Device b = fullDevice();
            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        @DisplayName("Devices with different deviceId are not equal")
        void differentDeviceIdNotEqual() {
            Device a = fullDevice();
            DeviceId otherId = DeviceId.of(new Ulid(0x0192A3B4C5D6E7FFL, 0x0102030405060708L));
            Device b = new Device(otherId, "kitchen-bulb", "Kitchen Bulb", "IKEA",
                    "TRADFRI-E27", "SN-12345", "1.4.2", "rev-B",
                    INTEGRATION_ID, AREA_ID, VIA_DEVICE_ID,
                    List.of("lighting", "kitchen"),
                    List.of(new HardwareIdentifier("zigbee_ieee", "00:11:22:33:44:55:66:77")),
                    CREATED_AT);
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("Device is not equal to null")
        void notEqualToNull() {
            assertThat(fullDevice()).isNotEqualTo(null);
        }
    }
}
