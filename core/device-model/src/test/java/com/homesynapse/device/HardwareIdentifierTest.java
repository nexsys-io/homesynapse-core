/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link HardwareIdentifier} — protocol-level device identifier.
 */
@DisplayName("HardwareIdentifier")
class HardwareIdentifierTest {

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {

        @Test
        @DisplayName("both fields accessible after construction")
        void fieldsAccessible() {
            var id = new HardwareIdentifier("zigbee_ieee", "00:11:22:33:44:55:66:77");
            assertThat(id.namespace()).isEqualTo("zigbee_ieee");
            assertThat(id.value()).isEqualTo("00:11:22:33:44:55:66:77");
        }

        @Test
        @DisplayName("record has exactly 2 components")
        void exactlyTwoFields() {
            assertThat(HardwareIdentifier.class.getRecordComponents()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("Null validation")
    class NullValidationTests {

        @Test
        @DisplayName("null namespace throws NullPointerException")
        void nullNamespace() {
            assertThatNullPointerException().isThrownBy(() ->
                    new HardwareIdentifier(null, "value"))
                    .withMessageContaining("namespace");
        }

        @Test
        @DisplayName("null value throws NullPointerException")
        void nullValue() {
            assertThatNullPointerException().isThrownBy(() ->
                    new HardwareIdentifier("ns", null))
                    .withMessageContaining("value");
        }
    }

    @Nested
    @DisplayName("Equals and hashCode")
    class EqualsHashCodeTests {

        @Test
        @DisplayName("identical identifiers are equal")
        void identicalEqual() {
            var a = new HardwareIdentifier("zigbee_ieee", "AA:BB");
            var b = new HardwareIdentifier("zigbee_ieee", "AA:BB");
            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        @DisplayName("different namespace not equal")
        void differentNamespace() {
            var a = new HardwareIdentifier("zigbee_ieee", "AA:BB");
            var b = new HardwareIdentifier("zwave_node", "AA:BB");
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("different value not equal")
        void differentValue() {
            var a = new HardwareIdentifier("zigbee_ieee", "AA:BB");
            var b = new HardwareIdentifier("zigbee_ieee", "CC:DD");
            assertThat(a).isNotEqualTo(b);
        }
    }
}
