/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ProcessingMode} — LIVE / REPLAY / PROJECTION / DRY_RUN.
 */
@DisplayName("ProcessingMode")
class ProcessingModeTest {

    @Test
    @DisplayName("exactly 4 enum values")
    void exactlyFourValues() {
        assertThat(ProcessingMode.values()).hasSize(4);
    }

    @Test
    @DisplayName("all expected values present in order")
    void expectedValues() {
        assertThat(ProcessingMode.values()).containsExactly(
                ProcessingMode.LIVE,
                ProcessingMode.REPLAY,
                ProcessingMode.PROJECTION,
                ProcessingMode.DRY_RUN);
    }

    @Test
    @DisplayName("valueOf round-trip for each value")
    void valueOfRoundTrip() {
        for (ProcessingMode mode : ProcessingMode.values()) {
            assertThat(ProcessingMode.valueOf(mode.name())).isEqualTo(mode);
        }
    }
}
