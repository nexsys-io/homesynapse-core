// HomeSynapse Core / Copyright (c) 2026 NexSys. All rights reserved.
package com.homesynapse.device;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Tests for {@link ConfirmationResult} — command confirmation evaluation outcome.
 */
@DisplayName("ConfirmationResult")
class ConfirmationResultTest {

    @Test
    @DisplayName("exactly 4 values declared")
    void exactlyFourValues() {
        assertThat(ConfirmationResult.values()).hasSize(4);
    }

    @Test
    @DisplayName("all expected values present in order")
    void allExpectedValues() {
        assertThat(ConfirmationResult.values()).containsExactly(
                ConfirmationResult.CONFIRMED,
                ConfirmationResult.NOT_YET,
                ConfirmationResult.FAILED,
                ConfirmationResult.TIMEOUT);
    }

    @ParameterizedTest
    @EnumSource(ConfirmationResult.class)
    @DisplayName("valueOf round-trip for each value")
    void valueOfRoundTrip(ConfirmationResult result) {
        assertThat(ConfirmationResult.valueOf(result.name())).isEqualTo(result);
    }
}
