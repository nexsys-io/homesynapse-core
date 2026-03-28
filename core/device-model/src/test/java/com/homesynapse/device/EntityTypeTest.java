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
 * Tests for {@link EntityType} — functional classification of device entities.
 */
@DisplayName("EntityType")
class EntityTypeTest {

    @Test
    @DisplayName("exactly 6 MVP values declared")
    void exactlySixValues() {
        assertThat(EntityType.values()).hasSize(6);
    }

    @Test
    @DisplayName("all expected MVP values present")
    void allExpectedValuesPresent() {
        assertThat(EntityType.values()).containsExactly(
                EntityType.LIGHT,
                EntityType.SWITCH,
                EntityType.PLUG,
                EntityType.SENSOR,
                EntityType.BINARY_SENSOR,
                EntityType.ENERGY_METER);
    }

    @ParameterizedTest
    @EnumSource(EntityType.class)
    @DisplayName("valueOf round-trip for each value")
    void valueOfRoundTrip(EntityType type) {
        assertThat(EntityType.valueOf(type.name())).isEqualTo(type);
    }
}
