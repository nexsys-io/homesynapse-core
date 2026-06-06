/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the value count and declaration order of {@link IsolationLevel} (AMD-63).
 * {@code IN_JVM} must be first (the MVP default); {@code RESERVED_SUBPROCESS}
 * second (the reservation slot).
 */
@DisplayName("IsolationLevel")
class IsolationLevelTest {

    /** Default constructor required by JUnit 5 under {@code -Xlint:all -Werror}. */
    IsolationLevelTest() {
        // Defaults are sufficient.
    }

    @Test
    @DisplayName("has exactly 2 values in declaration order")
    void hasTwoValuesInOrder() {
        assertThat(IsolationLevel.values())
                .containsExactly(
                        IsolationLevel.IN_JVM,
                        IsolationLevel.RESERVED_SUBPROCESS);
    }
}
