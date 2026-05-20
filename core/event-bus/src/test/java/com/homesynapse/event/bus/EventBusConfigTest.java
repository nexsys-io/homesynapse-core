/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event.bus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link EventBusConfig} record (M3.6b).
 *
 * <p>Covers the {@link EventBusConfig#HOME_DEFAULT} constant's exact
 * values (which must reproduce the prior M3.4b behaviour) and the compact
 * constructor's validation of both fields against the {@code >= 1}
 * invariant.</p>
 */
@DisplayName("EventBusConfig")
class EventBusConfigTest {

    /** Creates a new test instance. */
    EventBusConfigTest() {
        // Explicit constructor per -Xlint:all -Werror requirement.
    }

    @Test
    @DisplayName("HOME_DEFAULT exposes the prior hard-coded values")
    void homeDefaultValues() {
        // The default constant preserves the M3.4b behaviour: 10,000 replay
        // queue capacity (was ReplayWindowQueue.MAX_CAPACITY) and 5,000
        // publisher-blocked depth threshold (was the same-named constant on
        // InProcessEventBus).
        assertThat(EventBusConfig.HOME_DEFAULT.replayQueueCapacity())
                .as("HOME_DEFAULT.replayQueueCapacity preserves M3.4b default")
                .isEqualTo(10_000);
        assertThat(EventBusConfig.HOME_DEFAULT.publisherBlockedDepthThreshold())
                .as("HOME_DEFAULT.publisherBlockedDepthThreshold preserves M3.4b default")
                .isEqualTo(5_000);
    }

    @Test
    @DisplayName("compact constructor rejects zero replay queue capacity")
    void validationRejectsZeroCapacity() {
        assertThatThrownBy(() -> new EventBusConfig(0, 5_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("replayQueueCapacity");
    }

    @Test
    @DisplayName("compact constructor rejects zero publisher-blocked threshold")
    void validationRejectsZeroThreshold() {
        assertThatThrownBy(() -> new EventBusConfig(10_000, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("publisherBlockedDepthThreshold");
    }

    @Test
    @DisplayName("compact constructor rejects negative replay queue capacity")
    void validationRejectsNegativeCapacity() {
        assertThatThrownBy(() -> new EventBusConfig(-1, 5_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("replayQueueCapacity");
    }
}
