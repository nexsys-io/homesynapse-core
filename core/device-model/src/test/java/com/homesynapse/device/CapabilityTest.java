/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Capability} sealed interface — hierarchy structure.
 */
@DisplayName("Capability sealed interface")
class CapabilityTest {

    @Test
    @DisplayName("Capability is a sealed interface")
    void isSealed() {
        assertThat(Capability.class.isSealed()).isTrue();
    }

    @Test
    @DisplayName("exactly 17 permitted subtypes (16 standard records + CustomCapability)")
    void exactlySeventeenPermits() {
        // M9.4b §3.1 (SD-3): Identify joined the sealed set.
        assertThat(Capability.class.getPermittedSubclasses()).hasSize(17);
    }

    @Test
    @DisplayName("all 17 permitted subtypes are present")
    void allPermittedSubtypes() {
        Class<?>[] permitted = Capability.class.getPermittedSubclasses();
        assertThat(permitted).extracting(Class::getSimpleName)
                .containsExactlyInAnyOrder(
                        "OnOff", "Brightness", "ColorTemperature",
                        "TemperatureMeasurement", "HumidityMeasurement",
                        "IlluminanceMeasurement", "PowerMeasurement",
                        "BinaryState", "Contact", "Motion", "Occupancy",
                        "Battery", "DeviceHealth",
                        "EnergyMeter", "PowerMeter", "Identify",
                        "CustomCapability");
    }

    @Test
    @DisplayName("Capability is an interface")
    void isInterface() {
        assertThat(Capability.class.isInterface()).isTrue();
    }

    @Test
    @DisplayName("16 standard permits are records")
    void standardPermitsAreRecords() {
        Class<?>[] permitted = Capability.class.getPermittedSubclasses();
        long recordCount = java.util.Arrays.stream(permitted)
                .filter(Class::isRecord)
                .count();
        assertThat(recordCount).isEqualTo(16);
    }

    @Test
    @DisplayName("CustomCapability is NOT a record")
    void customCapabilityIsNotRecord() {
        assertThat(CustomCapability.class.isRecord()).isFalse();
    }
}
