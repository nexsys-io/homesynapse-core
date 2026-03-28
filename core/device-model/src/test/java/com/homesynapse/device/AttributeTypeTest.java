// HomeSynapse Core / Copyright (c) 2026 NexSys. All rights reserved.
package com.homesynapse.device;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Tests for {@link AttributeType} — primitive data type classifier.
 */
@DisplayName("AttributeType")
class AttributeTypeTest {

    @Test
    @DisplayName("exactly 5 values declared")
    void exactlyFiveValues() {
        assertThat(AttributeType.values()).hasSize(5);
    }

    @Test
    @DisplayName("all expected values present in order")
    void allExpectedValues() {
        assertThat(AttributeType.values()).containsExactly(
                AttributeType.BOOLEAN,
                AttributeType.INT,
                AttributeType.FLOAT,
                AttributeType.STRING,
                AttributeType.ENUM);
    }

    @ParameterizedTest
    @EnumSource(AttributeType.class)
    @DisplayName("valueOf round-trip for each value")
    void valueOfRoundTrip(AttributeType type) {
        assertThat(AttributeType.valueOf(type.name())).isEqualTo(type);
    }
}
