/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Tests for {@link AttributeType} — primitive data type classifier (AMD-47 §2.5).
 */
@DisplayName("AttributeType")
class AttributeTypeTest {

    @Test
    @DisplayName("exactly 8 values declared")
    void exactlyEightValues() {
        assertThat(AttributeType.values()).hasSize(8);
    }

    @Test
    @DisplayName("all expected values present in declared order")
    void allExpectedValues() {
        assertThat(AttributeType.values()).containsExactly(
                AttributeType.BOOLEAN,
                AttributeType.INT,
                AttributeType.FLOAT,
                AttributeType.STRING,
                AttributeType.ENUM,
                AttributeType.QUANTITY,
                AttributeType.ARRAY,
                AttributeType.DEGRADED);
    }

    @ParameterizedTest
    @EnumSource(AttributeType.class)
    @DisplayName("valueOf round-trip for each value")
    void valueOfRoundTrip(AttributeType type) {
        assertThat(AttributeType.valueOf(type.name())).isEqualTo(type);
    }
}
