/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * Tests for {link HumidityMeasurement} — standard capability record.
 */
@DisplayName("HumidityMeasurement")
class HumidityMeasurementTest {

    private static final ConfirmationPolicy POLICY = new ConfirmationPolicy(
            ConfirmationMode.DISABLED, List.of("humidity_pct"), null, 5000L);

    private static HumidityMeasurement sample() {
        return new HumidityMeasurement("humidity_measurement", 1, "core", Map.of(), Map.of(), POLICY);
    }

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {

        @Test
        @DisplayName("all 6 fields accessible after construction")
        void allFieldsAccessible() {
            HumidityMeasurement cap = sample();
            assertThat(cap.capabilityId()).isEqualTo("humidity_measurement");
            assertThat(cap.version()).isEqualTo(1);
            assertThat(cap.namespace()).isEqualTo("core");
            assertThat(cap.attributeSchemas()).isEmpty();
            assertThat(cap.commandDefinitions()).isEmpty();
            assertThat(cap.confirmationPolicy()).isEqualTo(POLICY);
        }

        @Test
        @DisplayName("record has exactly 6 components")
        void exactlySixFields() {
            assertThat(HumidityMeasurement.class.getRecordComponents()).hasSize(6);
        }

        @Test
        @DisplayName("implements Capability")
        void implementsCapability() {
            assertThat(sample()).isInstanceOf(Capability.class);
        }

        @Test
        @DisplayName("is a record")
        void isRecord() {
            assertThat(HumidityMeasurement.class.isRecord()).isTrue();
        }
    }

    @Nested
    @DisplayName("Equals and hashCode")
    class EqualsHashCodeTests {

        @Test
        @DisplayName("identical instances are equal")
        void identicalEqual() {
            assertThat(sample()).isEqualTo(sample());
            assertThat(sample().hashCode()).isEqualTo(sample().hashCode());
        }

        @Test
        @DisplayName("different capabilityId not equal")
        void differentNotEqual() {
            HumidityMeasurement a = sample();
            HumidityMeasurement b = new HumidityMeasurement("other", 1, "core", Map.of(), Map.of(), POLICY);
            assertThat(a).isNotEqualTo(b);
        }
    }
}
