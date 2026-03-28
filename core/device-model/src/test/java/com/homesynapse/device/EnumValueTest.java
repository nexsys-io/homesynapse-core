// HomeSynapse Core / Copyright (c) 2026 NexSys. All rights reserved.
package com.homesynapse.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link EnumValue} — constrained enum string attribute value record.
 */
@DisplayName("EnumValue")
class EnumValueTest {

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {

        @Test
        @DisplayName("value accessible after construction")
        void valueAccessible() {
            assertThat(new EnumValue("heating").value()).isEqualTo("heating");
        }

        @Test
        @DisplayName("record has exactly 1 component")
        void exactlyOneField() {
            assertThat(EnumValue.class.getRecordComponents()).hasSize(1);
        }

        @Test
        @DisplayName("implements AttributeValue")
        void implementsAttributeValue() {
            assertThat(new EnumValue("idle")).isInstanceOf(AttributeValue.class);
        }
    }

    @Nested
    @DisplayName("Null validation")
    class NullValidationTests {

        @Test
        @DisplayName("null value throws NullPointerException")
        void nullValueThrows() {
            assertThatNullPointerException().isThrownBy(() ->
                    new EnumValue(null))
                    .withMessageContaining("value");
        }
    }

    @Nested
    @DisplayName("AttributeValue contract")
    class ContractTests {

        @Test
        @DisplayName("rawValue returns String")
        void rawValueReturnsString() {
            assertThat(new EnumValue("import").rawValue()).isEqualTo("import");
        }

        @Test
        @DisplayName("attributeType returns ENUM")
        void attributeTypeIsEnum() {
            assertThat(new EnumValue("idle").attributeType()).isEqualTo(AttributeType.ENUM);
        }
    }

    @Nested
    @DisplayName("Equals and hashCode")
    class EqualsHashCodeTests {

        @Test
        @DisplayName("identical values are equal")
        void identicalEqual() {
            assertThat(new EnumValue("heating")).isEqualTo(new EnumValue("heating"));
            assertThat(new EnumValue("heating").hashCode())
                    .isEqualTo(new EnumValue("heating").hashCode());
        }

        @Test
        @DisplayName("different values are not equal")
        void differentNotEqual() {
            assertThat(new EnumValue("heating")).isNotEqualTo(new EnumValue("cooling"));
        }
    }
}
