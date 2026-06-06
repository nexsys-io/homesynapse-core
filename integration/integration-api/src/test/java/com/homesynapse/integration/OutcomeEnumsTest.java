/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the value count and declaration order of the three AMD-55 hook-outcome
 * enums. Declaration order is load-bearing (the AMD-44/B-S2 source-order lesson),
 * so {@code containsExactly} guards both count and order.
 */
@DisplayName("AMD-55 outcome enums")
class OutcomeEnumsTest {

    /** Default constructor required by JUnit 5 under {@code -Xlint:all -Werror}. */
    OutcomeEnumsTest() {
        // Defaults are sufficient.
    }

    @Test
    @DisplayName("ConfigUpdateOutcome has exactly 3 values in order")
    void configUpdateOutcome_threeValuesInOrder() {
        assertThat(ConfigUpdateOutcome.values())
                .containsExactly(
                        ConfigUpdateOutcome.APPLIED,
                        ConfigUpdateOutcome.RESTART_REQUIRED,
                        ConfigUpdateOutcome.REJECTED);
    }

    @Test
    @DisplayName("MigrationOutcome has exactly 2 values in order")
    void migrationOutcome_twoValuesInOrder() {
        assertThat(MigrationOutcome.values())
                .containsExactly(
                        MigrationOutcome.MIGRATED,
                        MigrationOutcome.NOT_REQUIRED);
    }

    @Test
    @DisplayName("ReauthOutcome has exactly 2 values in order")
    void reauthOutcome_twoValuesInOrder() {
        assertThat(ReauthOutcome.values())
                .containsExactly(
                        ReauthOutcome.INITIATED,
                        ReauthOutcome.UNSUPPORTED);
    }
}
