// HomeSynapse Core / Copyright (c) 2026 NexSys. All rights reserved.
package com.homesynapse.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link StringValue} — free-form string attribute value record.
 */
@DisplayName("StringValue")
class StringValueTest {

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {

        @Test
        @DisplayName("value accessible after construction")
        void valueAccessible() {
            assertThat(new StringValue("firmware-1.4.2").value()).isEqualTo("firmware-1.4.2");
        }

        @Test
        @DisplayName("empty string is valid")
        void emptyStringValid() {
            assertThat(new StringValue("").value()).isEmpty();
        }

        @Test
        @DisplayName("record has exactly 1 component")
        void exactlyOneField() {
            assertThat(StringValue.class.getRecordComponents()).hasSize(1);
        }

        @Test
        @DisplayName("implements AttributeValue")
        void implementsAttributeValue() {
            assertThat(new StringValue("test")).isInstanceOf(AttributeValue.class);
        }
    }

    @Nested
    @DisplayName("Null validation")
    class NullValidationTests {

        @Test
        @DisplayName("null value throws NullPointerException")
        void nullValueThrows() {
            assertThatNullPointerException().isThrownBy(() ->
                    new StringValue(null))
                    .withMessageContaining("value");
        }
    }

    @Nested
    @DisplayName("AttributeValue contract")
    class ContractTests {

        @Test
        @DisplayName("rawValue returns String")
        void rawValueReturnsString() {
            assertThat(new StringValue("hello").rawValue()).isEqualTo("hello");
        }

        @Test
        @DisplayName("attributeType returns STRING")
        void attributeTypeIsString() {
            assertThat(new StringValue("test").attributeType()).isEqualTo(AttributeType.STRING);
        }
    }

    @Nested
    @DisplayName("Equals and hashCode")
    class EqualsHashCodeTests {

        @Test
        @DisplayName("identical values are equal")
        void identicalEqual() {
            assertThat(new StringValue("abc")).isEqualTo(new StringValue("abc"));
            assertThat(new StringValue("abc").hashCode())
                    .isEqualTo(new StringValue("abc").hashCode());
        }

        @Test
        @DisplayName("different values are not equal")
        void differentNotEqual() {
            assertThat(new StringValue("abc")).isNotEqualTo(new StringValue("xyz"));
        }
    }
}
