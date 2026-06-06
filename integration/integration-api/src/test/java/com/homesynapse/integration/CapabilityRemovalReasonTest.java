/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the value count and declaration order of {@link CapabilityRemovalReason}
 * (AMD-59 §2.2, ratification edit E8). Order is load-bearing.
 */
@DisplayName("CapabilityRemovalReason")
class CapabilityRemovalReasonTest {

    /** Default constructor required by JUnit 5 under {@code -Xlint:all -Werror}. */
    CapabilityRemovalReasonTest() {
        // Defaults are sufficient.
    }

    @Test
    @DisplayName("has exactly 4 values in declaration order")
    void hasFourValuesInOrder() {
        assertThat(CapabilityRemovalReason.values())
                .containsExactly(
                        CapabilityRemovalReason.FIRMWARE_DOWNGRADE,
                        CapabilityRemovalReason.DEVICE_REPLACED,
                        CapabilityRemovalReason.TRANSIENT_LOSS,
                        CapabilityRemovalReason.UNREGISTERED);
    }
}
