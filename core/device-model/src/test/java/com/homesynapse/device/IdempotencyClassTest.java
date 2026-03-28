// HomeSynapse Core / Copyright (c) 2026 NexSys. All rights reserved.
package com.homesynapse.device;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Tests for {@link IdempotencyClass} — command idempotency classification.
 */
@DisplayName("IdempotencyClass")
class IdempotencyClassTest {

    @Test
    @DisplayName("exactly 3 values declared")
    void exactlyThreeValues() {
        assertThat(IdempotencyClass.values()).hasSize(3);
    }

    @Test
    @DisplayName("all expected values present in order")
    void allExpectedValues() {
        assertThat(IdempotencyClass.values()).containsExactly(
                IdempotencyClass.IDEMPOTENT,
                IdempotencyClass.NOT_IDEMPOTENT,
                IdempotencyClass.CONDITIONAL);
    }

    @ParameterizedTest
    @EnumSource(IdempotencyClass.class)
    @DisplayName("valueOf round-trip for each value")
    void valueOfRoundTrip(IdempotencyClass cls) {
        assertThat(IdempotencyClass.valueOf(cls.name())).isEqualTo(cls);
    }
}
