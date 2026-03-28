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
 * Tests for {@link BooleanValue} — boolean attribute value record.
 */
@DisplayName("BooleanValue")
class BooleanValueTest {

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {

        @Test
        @DisplayName("true value accessible")
        void trueValue() {
            var bv = new BooleanValue(true);
            assertThat(bv.value()).isTrue();
        }

        @Test
        @DisplayName("false value accessible")
        void falseValue() {
            var bv = new BooleanValue(false);
            assertThat(bv.value()).isFalse();
        }

        @Test
        @DisplayName("record has exactly 1 component")
        void exactlyOneField() {
            assertThat(BooleanValue.class.getRecordComponents()).hasSize(1);
        }

        @Test
        @DisplayName("implements AttributeValue")
        void implementsAttributeValue() {
            assertThat(new BooleanValue(true)).isInstanceOf(AttributeValue.class);
        }
    }

    @Nested
    @DisplayName("AttributeValue contract")
    class ContractTests {

        @Test
        @DisplayName("rawValue returns Boolean")
        void rawValueReturnsBoolean() {
            assertThat(new BooleanValue(true).rawValue()).isEqualTo(true);
            assertThat(new BooleanValue(false).rawValue()).isEqualTo(false);
        }

        @Test
        @DisplayName("attributeType returns BOOLEAN")
        void attributeTypeIsBoolean() {
            assertThat(new BooleanValue(true).attributeType()).isEqualTo(AttributeType.BOOLEAN);
        }
    }

    @Nested
    @DisplayName("Equals and hashCode")
    class EqualsHashCodeTests {

        @Test
        @DisplayName("identical values are equal")
        void identicalEqual() {
            assertThat(new BooleanValue(true)).isEqualTo(new BooleanValue(true));
            assertThat(new BooleanValue(true).hashCode())
                    .isEqualTo(new BooleanValue(true).hashCode());
        }

        @Test
        @DisplayName("different values are not equal")
        void differentNotEqual() {
            assertThat(new BooleanValue(true)).isNotEqualTo(new BooleanValue(false));
        }
    }
}
