/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the value count and declaration order of {@link ExceptionClassification}
 * after the AMD-56 append of {@code AUTH_FAILED}. The enum is append-only — the
 * original three keep their positions and {@code AUTH_FAILED} is last.
 */
@DisplayName("ExceptionClassification")
class ExceptionClassificationTest {

    /** Default constructor required by JUnit 5 under {@code -Xlint:all -Werror}. */
    ExceptionClassificationTest() {
        // Defaults are sufficient.
    }

    @Test
    @DisplayName("has exactly 4 values in append-only declaration order")
    void hasFourValuesInOrder() {
        assertThat(ExceptionClassification.values())
                .containsExactly(
                        ExceptionClassification.TRANSIENT,
                        ExceptionClassification.PERMANENT,
                        ExceptionClassification.SHUTDOWN_SIGNAL,
                        ExceptionClassification.AUTH_FAILED);
    }

    @Test
    @DisplayName("AUTH_FAILED is appended last (ordinal 3)")
    void authFailedIsLast() {
        assertThat(ExceptionClassification.AUTH_FAILED.ordinal()).isEqualTo(3);
    }
}
