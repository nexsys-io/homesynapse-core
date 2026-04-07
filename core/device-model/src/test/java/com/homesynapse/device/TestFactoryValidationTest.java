/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import com.homesynapse.device.test.TestCapabilityFactory;
import com.homesynapse.device.test.TestDeviceFactory;
import com.homesynapse.device.test.TestEntityFactory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates that the device-model test factories produce well-formed, contract-correct
 * instances suitable for downstream test consumption.
 *
 * <p>These tests verify structural correctness of factory output — that required fields
 * are populated, types match expectations, and convenience methods wire the correct
 * capability instances for each entity type.</p>
 *
 * @see TestDeviceFactory
 * @see TestEntityFactory
 * @see TestCapabilityFactory
 */
@DisplayName("Device Model Test Factory Validation")
class TestFactoryValidationTest {

    /** Creates a new test instance. */
    TestFactoryValidationTest() {
        // Explicit constructor per -Xlint:all -Werror requirement.
    }

    // ──────────────────────────────────────────────────────────────────
    // SECTION 1: TestDeviceFactory validation
    // ──────────────────────────────────────────────────────────────────

    /**
     * Verifies that {@link TestDeviceFactory#device()} produces a valid Device
     * with all 14 fields populated to sensible defaults — non-null for required
     * fields and appropriate defaults for nullable fields.
     */
    @Test
    @DisplayName("TestDeviceFactory.device() produces valid Device with all required fields")
    void deviceFactory_defaultDevice_hasAllRequiredFields() {
        Device device = TestDeviceFactory.device();

        assertThat(device.deviceId()).isNotNull();
        assertThat(device.deviceSlug()).isEqualTo("test-device");
        assertThat(device.displayName()).isEqualTo("Test Device");
        assertThat(device.manufacturer()).isEqualTo("TestMfr");
        assertThat(device.model()).isEqualTo("TestModel-1");
        assertThat(device.integrationId()).isNotNull();
        assertThat(device.labels()).isNotNull();
        assertThat(device.hardwareIdentifiers()).isNotNull().isNotEmpty();
        assertThat(device.createdAt()).isNotNull();
    }

    /**
     * Verifies that the {@link TestDeviceFactory.DeviceBuilder} allows
     * field customization and that overridden values take effect.
     */
    @Test
    @DisplayName("TestDeviceFactory.builder() customization overrides defaults")
    void deviceFactory_builderCustomization_overridesDefaults() {
        Device device = TestDeviceFactory.builder()
                .deviceSlug("custom-slug")
                .displayName("Custom Device")
                .manufacturer("CustomMfr")
                .build();

        assertThat(device.deviceSlug()).isEqualTo("custom-slug");
        assertThat(device.displayName()).isEqualTo("Custom Device");
        assertThat(device.manufacturer()).isEqualTo("CustomMfr");
        // Non-overridden fields retain defaults
        assertThat(device.model()).isEqualTo("TestModel-1");
        assertThat(device.deviceId()).isNotNull();
    }

    // ──────────────────────────────────────────────────────────────────
    // SECTION 2: TestEntityFactory validation
    // ──────────────────────────────────────────────────────────────────

    /**
     * Verifies that {@link TestEntityFactory#entity()} produces a valid Entity
     * with all 11 fields populated to sensible defaults.
     */
    @Test
    @DisplayName("TestEntityFactory.entity() produces valid Entity with all required fields")
    void entityFactory_defaultEntity_hasAllRequiredFields() {
        Entity entity = TestEntityFactory.entity();

        assertThat(entity.entityId()).isNotNull();
        assertThat(entity.entitySlug()).isEqualTo("test-entity");
        assertThat(entity.entityType()).isEqualTo(EntityType.LIGHT);
        assertThat(entity.displayName()).isEqualTo("Test Entity");
        assertThat(entity.deviceId()).isNotNull();
        assertThat(entity.endpointIndex()).isEqualTo(1);
        assertThat(entity.enabled()).isTrue();
        assertThat(entity.labels()).isNotNull();
        assertThat(entity.capabilities()).isNotNull();
        assertThat(entity.createdAt()).isNotNull();
    }

    /**
     * Verifies that {@link TestEntityFactory#light()} creates a LIGHT entity
     * with an OnOff capability instance — the required capability per Doc 02 §3.
     */
    @Test
    @DisplayName("TestEntityFactory.light() creates LIGHT with OnOff capability")
    void entityFactory_light_hasOnOffCapability() {
        Entity light = TestEntityFactory.light();

        assertThat(light.entityType()).isEqualTo(EntityType.LIGHT);
        assertThat(light.capabilities()).hasSize(1);
        assertThat(light.capabilities().get(0).capabilityId()).isEqualTo("on_off");
    }

    /**
     * Verifies that {@link TestEntityFactory#sensor()} creates a SENSOR entity
     * with a TemperatureMeasurement capability — satisfying the "at least one
     * measurement capability" requirement per Doc 02 §3.
     */
    @Test
    @DisplayName("TestEntityFactory.sensor() creates SENSOR with measurement capability")
    void entityFactory_sensor_hasMeasurementCapability() {
        Entity sensor = TestEntityFactory.sensor();

        assertThat(sensor.entityType()).isEqualTo(EntityType.SENSOR);
        assertThat(sensor.capabilities()).hasSize(1);
        assertThat(sensor.capabilities().get(0).capabilityId())
                .isEqualTo("temperature_measurement");
    }

    /**
     * Verifies that {@link TestEntityFactory#binarySensor()} creates a
     * BINARY_SENSOR entity with a Contact capability — satisfying the
     * "at least one binary capability" requirement per Doc 02 §3.
     */
    @Test
    @DisplayName("TestEntityFactory.binarySensor() creates BINARY_SENSOR with binary capability")
    void entityFactory_binarySensor_hasBinaryCapability() {
        Entity binarySensor = TestEntityFactory.binarySensor();

        assertThat(binarySensor.entityType()).isEqualTo(EntityType.BINARY_SENSOR);
        assertThat(binarySensor.capabilities()).hasSize(1);
        assertThat(binarySensor.capabilities().get(0).capabilityId())
                .isEqualTo("contact");
    }

    // ──────────────────────────────────────────────────────────────────
    // SECTION 3: TestCapabilityFactory validation
    // ──────────────────────────────────────────────────────────────────

    /**
     * Verifies that {@link TestCapabilityFactory#onOff()} produces a valid
     * OnOff capability with correct schema — the "on" attribute, all three
     * commands, and EXACT_MATCH confirmation policy.
     */
    @Test
    @DisplayName("TestCapabilityFactory.onOff() has correct schema and confirmation")
    void capabilityFactory_onOff_hasCorrectSchema() {
        OnOff onOff = TestCapabilityFactory.onOff();

        assertThat(onOff.capabilityId()).isEqualTo("on_off");
        assertThat(onOff.version()).isEqualTo(1);
        assertThat(onOff.namespace()).isEqualTo("core");
        assertThat(onOff.attributeSchemas()).containsKey("on");
        assertThat(onOff.commandDefinitions()).containsKeys("turn_on", "turn_off", "toggle");
        assertThat(onOff.confirmationPolicy().mode()).isEqualTo(ConfirmationMode.EXACT_MATCH);
    }

    /**
     * Verifies that {@link TestCapabilityFactory#instanceOf(Capability)} correctly
     * converts a Capability into a CapabilityInstance, preserving all fields
     * and setting featureMap to 0.
     */
    @Test
    @DisplayName("TestCapabilityFactory.instanceOf() converts Capability to CapabilityInstance")
    void capabilityFactory_instanceOf_convertsCorrectly() {
        OnOff onOff = TestCapabilityFactory.onOff();
        CapabilityInstance instance = TestCapabilityFactory.instanceOf(onOff);

        assertThat(instance.capabilityId()).isEqualTo(onOff.capabilityId());
        assertThat(instance.version()).isEqualTo(onOff.version());
        assertThat(instance.namespace()).isEqualTo(onOff.namespace());
        assertThat(instance.featureMap()).isZero();
        assertThat(instance.attributes()).isEqualTo(onOff.attributeSchemas());
        assertThat(instance.commands()).isEqualTo(onOff.commandDefinitions());
        assertThat(instance.confirmation()).isEqualTo(onOff.confirmationPolicy());
    }
}
