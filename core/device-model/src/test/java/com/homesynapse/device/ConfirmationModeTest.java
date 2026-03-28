// HomeSynapse Core / Copyright (c) 2026 NexSys. All rights reserved.
package com.homesynapse.device;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Tests for {@link ConfirmationMode} — command confirmation comparison strategy.
 */
@DisplayName("ConfirmationMode")
class ConfirmationModeTest {

    @Test
    @DisplayName("exactly 5 values declared")
    void exactlyFiveValues() {
        assertThat(ConfirmationMode.values()).hasSize(5);
    }

    @Test
    @DisplayName("all expected values present in order")
    void allExpectedValues() {
        assertThat(ConfirmationMode.values()).containsExactly(
                ConfirmationMode.EXACT_MATCH,
                ConfirmationMode.TOLERANCE,
                ConfirmationMode.ENUM_MATCH,
                ConfirmationMode.ANY_CHANGE,
                ConfirmationMode.DISABLED);
    }

    @ParameterizedTest
    @EnumSource(ConfirmationMode.class)
    @DisplayName("valueOf round-trip for each value")
    void valueOfRoundTrip(ConfirmationMode mode) {
        assertThat(ConfirmationMode.valueOf(mode.name())).isEqualTo(mode);
    }
}
