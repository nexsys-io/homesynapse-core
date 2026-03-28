// HomeSynapse Core / Copyright (c) 2026 NexSys. All rights reserved.
package com.homesynapse.device;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link FloatValue} — floating-point attribute value record (uses double).
 */
@DisplayName("FloatValue")
class FloatValueTest {

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {

        @Test
        @DisplayName("positive value accessible")
        void positiveValue() {
            assertThat(new FloatValue(21.5).value()).isEqualTo(21.5);
        }

        @Test
        @DisplayName("zero value accessible")
        void zeroValue() {
            assertThat(new FloatValue(0.0).value()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("negative value accessible")
        void negativeValue() {
            assertThat(new FloatValue(-10.3).value()).isEqualTo(-10.3);
        }

        @Test
        @DisplayName("record has exactly 1 component")
        void exactlyOneField() {
            assertThat(FloatValue.class.getRecordComponents()).hasSize(1);
        }

        @Test
        @DisplayName("implements AttributeValue")
        void implementsAttributeValue() {
            assertThat(new FloatValue(0.0)).isInstanceOf(AttributeValue.class);
        }
    }

    @Nested
    @DisplayName("AttributeValue contract")
    class ContractTests {

        @Test
        @DisplayName("rawValue returns Double")
        void rawValueReturnsDouble() {
            assertThat(new FloatValue(22.5).rawValue()).isEqualTo(22.5);
        }

        @Test
        @DisplayName("attributeType returns FLOAT")
        void attributeTypeIsFloat() {
            assertThat(new FloatValue(0.0).attributeType()).isEqualTo(AttributeType.FLOAT);
        }
    }

    @Nested
    @DisplayName("Equals and hashCode")
    class EqualsHashCodeTests {

        @Test
        @DisplayName("identical values are equal")
        void identicalEqual() {
            assertThat(new FloatValue(21.5)).isEqualTo(new FloatValue(21.5));
            assertThat(new FloatValue(21.5).hashCode()).isEqualTo(new FloatValue(21.5).hashCode());
        }

        @Test
        @DisplayName("different values are not equal")
        void differentNotEqual() {
            assertThat(new FloatValue(0.0)).isNotEqualTo(new FloatValue(100.0));
        }
    }
}
