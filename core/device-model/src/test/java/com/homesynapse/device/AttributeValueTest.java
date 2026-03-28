// HomeSynapse Core / Copyright (c) 2026 NexSys. All rights reserved.
package com.homesynapse.device;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link AttributeValue} sealed interface — hierarchy structure.
 */
@DisplayName("AttributeValue sealed interface")
class AttributeValueTest {

    @Test
    @DisplayName("AttributeValue is a sealed interface")
    void isSealed() {
        assertThat(AttributeValue.class.isSealed()).isTrue();
    }

    @Test
    @DisplayName("exactly 5 permitted subtypes")
    void exactlyFivePermits() {
        assertThat(AttributeValue.class.getPermittedSubclasses()).hasSize(5);
    }

    @Test
    @DisplayName("permitted subtypes are BooleanValue, IntValue, FloatValue, StringValue, EnumValue")
    void permittedSubtypes() {
        Class<?>[] permitted = AttributeValue.class.getPermittedSubclasses();
        assertThat(permitted).extracting(Class::getSimpleName)
                .containsExactlyInAnyOrder(
                        "BooleanValue", "IntValue", "FloatValue",
                        "StringValue", "EnumValue");
    }

    @Test
    @DisplayName("AttributeValue is an interface")
    void isInterface() {
        assertThat(AttributeValue.class.isInterface()).isTrue();
    }
}
