/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CommandIdempotency} — IDEMPOTENT / NOT_IDEMPOTENT / CONDITIONAL.
 */
@DisplayName("CommandIdempotency")
class CommandIdempotencyTest {

    @Test
    @DisplayName("exactly 3 enum values")
    void exactlyThreeValues() {
        assertThat(CommandIdempotency.values()).hasSize(3);
    }

    @Test
    @DisplayName("all expected values present in order")
    void expectedValues() {
        assertThat(CommandIdempotency.values()).containsExactly(
                CommandIdempotency.IDEMPOTENT,
                CommandIdempotency.NOT_IDEMPOTENT,
                CommandIdempotency.CONDITIONAL);
    }

    @Test
    @DisplayName("CONDITIONAL exists (renamed from TOGGLE per Architecture Benchmark)")
    void conditionalExists() {
        assertThat(CommandIdempotency.valueOf("CONDITIONAL"))
                .isEqualTo(CommandIdempotency.CONDITIONAL);
    }

    @Test
    @DisplayName("valueOf round-trip for each value")
    void valueOfRoundTrip() {
        for (CommandIdempotency idempotency : CommandIdempotency.values()) {
            assertThat(CommandIdempotency.valueOf(idempotency.name()))
                    .isEqualTo(idempotency);
        }
    }
}
