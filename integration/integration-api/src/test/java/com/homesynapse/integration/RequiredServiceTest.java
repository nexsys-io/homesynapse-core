/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the value count and declaration order of {@link RequiredService} after the
 * AMD-59/60 append of {@code DISCOVERY} and {@code SECURITY}. The enum is
 * append-only — the original three must keep their positions so any persisted or
 * test-pinned ordinal stays stable.
 */
@DisplayName("RequiredService")
class RequiredServiceTest {

    /** Default constructor required by JUnit 5 under {@code -Xlint:all -Werror}. */
    RequiredServiceTest() {
        // Defaults are sufficient.
    }

    @Test
    @DisplayName("has exactly 5 values in append-only declaration order")
    void hasFiveValuesInOrder() {
        assertThat(RequiredService.values())
                .containsExactly(
                        RequiredService.HTTP_CLIENT,
                        RequiredService.SCHEDULER,
                        RequiredService.TELEMETRY_WRITER,
                        RequiredService.DISCOVERY,
                        RequiredService.SECURITY);
    }

    @Test
    @DisplayName("the original three retain their leading positions (append-only)")
    void originalThreeUnmoved() {
        assertThat(RequiredService.HTTP_CLIENT.ordinal()).isEqualTo(0);
        assertThat(RequiredService.SCHEDULER.ordinal()).isEqualTo(1);
        assertThat(RequiredService.TELEMETRY_WRITER.ordinal()).isEqualTo(2);
    }
}
