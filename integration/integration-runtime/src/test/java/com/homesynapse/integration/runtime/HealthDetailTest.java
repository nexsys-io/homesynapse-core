/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the value count and declaration order of {@link HealthDetail} (AMD-57).
 * The enum is append-only once ratified, and each value maps 1:1 to a supervisor
 * transition trigger (AMD-57-INV-02), so order is load-bearing.
 */
@DisplayName("HealthDetail")
class HealthDetailTest {

    /** Default constructor required by JUnit 5 under {@code -Xlint:all -Werror}. */
    HealthDetailTest() {
        // Defaults are sufficient.
    }

    @Test
    @DisplayName("has exactly 12 values in declaration order")
    void hasTwelveValuesInOrder() {
        assertThat(HealthDetail.values())
                .containsExactly(
                        HealthDetail.NONE,
                        HealthDetail.HEARTBEAT_TIMEOUT,
                        HealthDetail.KEEPALIVE_TIMEOUT,
                        HealthDetail.ERROR_RATE_EXCEEDED,
                        HealthDetail.TIMEOUT_RATE_EXCEEDED,
                        HealthDetail.SLOW_CALL_RATE_EXCEEDED,
                        HealthDetail.PROBE_FAILED,
                        HealthDetail.RESTART_LIMIT_EXCEEDED,
                        HealthDetail.SUSPENSION_LIMIT_EXCEEDED,
                        HealthDetail.RESOURCE_QUOTA_EXCEEDED,
                        HealthDetail.AUTH_FAILURE,
                        HealthDetail.PERMANENT_FAILURE);
    }

    @Test
    @DisplayName("NONE is the first value — the explicit no-cause sentinel")
    void noneIsFirst() {
        assertThat(HealthDetail.values()[0]).isEqualTo(HealthDetail.NONE);
    }
}
