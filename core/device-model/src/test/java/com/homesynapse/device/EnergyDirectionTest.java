// HomeSynapse Core / Copyright (c) 2026 NexSys. All rights reserved.
package com.homesynapse.device;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Tests for {@link EnergyDirection} — energy flow direction for metering.
 */
@DisplayName("EnergyDirection")
class EnergyDirectionTest {

    @Test
    @DisplayName("exactly 3 values declared")
    void exactlyThreeValues() {
        assertThat(EnergyDirection.values()).hasSize(3);
    }

    @Test
    @DisplayName("all expected values present in order")
    void allExpectedValues() {
        assertThat(EnergyDirection.values()).containsExactly(
                EnergyDirection.IMPORT,
                EnergyDirection.EXPORT,
                EnergyDirection.BIDIRECTIONAL);
    }

    @ParameterizedTest
    @EnumSource(EnergyDirection.class)
    @DisplayName("valueOf round-trip for each value")
    void valueOfRoundTrip(EnergyDirection direction) {
        assertThat(EnergyDirection.valueOf(direction.name())).isEqualTo(direction);
    }
}
