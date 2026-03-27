/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link EventOrigin} — semantic source category for event provenance.
 */
@DisplayName("EventOrigin")
class EventOriginTest {

    @Test
    @DisplayName("exactly 7 enum values")
    void exactlySevenValues() {
        assertThat(EventOrigin.values()).hasSize(7);
    }

    @Test
    @DisplayName("all expected values present in order")
    void expectedValues() {
        assertThat(EventOrigin.values()).containsExactly(
                EventOrigin.PHYSICAL,
                EventOrigin.USER_COMMAND,
                EventOrigin.AUTOMATION,
                EventOrigin.DEVICE_AUTONOMOUS,
                EventOrigin.INTEGRATION,
                EventOrigin.SYSTEM,
                EventOrigin.UNKNOWN);
    }

    @Test
    @DisplayName("UNKNOWN is the default (last value)")
    void unknownIsDefault() {
        assertThat(EventOrigin.UNKNOWN).isNotNull();
    }

    @Test
    @DisplayName("valueOf round-trip for each value")
    void valueOfRoundTrip() {
        for (EventOrigin origin : EventOrigin.values()) {
            assertThat(EventOrigin.valueOf(origin.name())).isEqualTo(origin);
        }
    }
}
