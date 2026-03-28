/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link IntValue} — integer attribute value record (uses long).
 */
@DisplayName("IntValue")
class IntValueTest {

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {

        @Test
        @DisplayName("positive value accessible")
        void positiveValue() {
            assertThat(new IntValue(100).value()).isEqualTo(100L);
        }

        @Test
        @DisplayName("zero value accessible")
        void zeroValue() {
            assertThat(new IntValue(0).value()).isEqualTo(0L);
        }

        @Test
        @DisplayName("negative value accessible")
        void negativeValue() {
            assertThat(new IntValue(-50).value()).isEqualTo(-50L);
        }

        @Test
        @DisplayName("long max value accessible")
        void longMaxValue() {
            assertThat(new IntValue(Long.MAX_VALUE).value()).isEqualTo(Long.MAX_VALUE);
        }

        @Test
        @DisplayName("record has exactly 1 component")
        void exactlyOneField() {
            assertThat(IntValue.class.getRecordComponents()).hasSize(1);
        }

        @Test
        @DisplayName("implements AttributeValue")
        void implementsAttributeValue() {
            assertThat(new IntValue(42)).isInstanceOf(AttributeValue.class);
        }
    }

    @Nested
    @DisplayName("AttributeValue contract")
    class ContractTests {

        @Test
        @DisplayName("rawValue returns Long")
        void rawValueReturnsLong() {
            assertThat(new IntValue(255).rawValue()).isEqualTo(255L);
        }

        @Test
        @DisplayName("attributeType returns INT")
        void attributeTypeIsInt() {
            assertThat(new IntValue(0).attributeType()).isEqualTo(AttributeType.INT);
        }
    }

    @Nested
    @DisplayName("Equals and hashCode")
    class EqualsHashCodeTests {

        @Test
        @DisplayName("identical values are equal")
        void identicalEqual() {
            assertThat(new IntValue(100)).isEqualTo(new IntValue(100));
            assertThat(new IntValue(100).hashCode()).isEqualTo(new IntValue(100).hashCode());
        }

        @Test
        @DisplayName("different values are not equal")
        void differentNotEqual() {
            assertThat(new IntValue(0)).isNotEqualTo(new IntValue(100));
        }
    }
}
