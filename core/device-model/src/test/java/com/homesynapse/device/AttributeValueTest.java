/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link AttributeValue} sealed interface — hierarchy structure (AMD-47-INV-01).
 */
@DisplayName("AttributeValue sealed interface")
class AttributeValueTest {

    @Test
    @DisplayName("AttributeValue is a sealed interface")
    void isSealed() {
        assertThat(AttributeValue.class.isSealed()).isTrue();
    }

    @Test
    @DisplayName("exactly 8 permitted subtypes")
    void exactlyEightPermits() {
        assertThat(AttributeValue.class.getPermittedSubclasses()).hasSize(8);
    }

    @Test
    @DisplayName("permitted subtypes are the five primitives plus QuantityValue, ArrayValue, DegradedAttributeValue")
    void permittedSubtypes() {
        Class<?>[] permitted = AttributeValue.class.getPermittedSubclasses();
        assertThat(permitted).extracting(Class::getSimpleName)
                .containsExactlyInAnyOrder(
                        "BooleanValue", "IntValue", "FloatValue",
                        "StringValue", "EnumValue",
                        "QuantityValue", "ArrayValue", "DegradedAttributeValue");
    }

    @Test
    @DisplayName("AttributeValue is an interface")
    void isInterface() {
        assertThat(AttributeValue.class.isInterface()).isTrue();
    }
}
