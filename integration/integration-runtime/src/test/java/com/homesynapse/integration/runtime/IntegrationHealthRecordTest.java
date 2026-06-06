/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.homesynapse.integration.HealthState;
import com.homesynapse.platform.identity.IntegrationId;
import com.homesynapse.platform.identity.Ulid;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

/**
 * Verifies the AMD-57 evolution of {@link IntegrationHealthRecord}: the
 * {@code detail} component lands immediately after {@code state} (component 3 of
 * 14) and is non-null-guarded.
 *
 * <p>Time values use literal {@link Instant#parse(CharSequence)} (the M4.B-S2
 * pattern) so the {@code NO_DIRECT_TIME_ACCESS} arch rule passes on this test
 * source.</p>
 */
@DisplayName("IntegrationHealthRecord")
class IntegrationHealthRecordTest {

    private static final IntegrationId ID =
            IntegrationId.of(Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAV"));
    private static final Instant T = Instant.parse("2026-01-01T00:00:00Z");
    private static final SlidingWindow WINDOW = new SlidingWindow(20, 0, 0.0);

    /** Default constructor required by JUnit 5 under {@code -Xlint:all -Werror}. */
    IntegrationHealthRecordTest() {
        // Defaults are sufficient.
    }

    private static IntegrationHealthRecord valid(HealthDetail detail) {
        return new IntegrationHealthRecord(
                ID, HealthState.HEALTHY, detail, 1.0,
                T, T, T, 0, 0, Duration.ZERO,
                WINDOW, WINDOW, WINDOW, false);
    }

    @Test
    @DisplayName("record has exactly 14 components")
    void hasFourteenComponents() {
        assertThat(IntegrationHealthRecord.class.getRecordComponents()).hasSize(14);
    }

    @Test
    @DisplayName("detail is the third component, immediately after state")
    void detailIsThirdComponentAfterState() {
        var components = IntegrationHealthRecord.class.getRecordComponents();
        assertThat(components[1].getName()).isEqualTo("state");
        assertThat(components[2].getName()).isEqualTo("detail");
    }

    @Test
    @DisplayName("a fully-populated record exposes detail()")
    void detailAccessorReturnsValue() {
        assertThat(valid(HealthDetail.AUTH_FAILURE).detail())
                .isEqualTo(HealthDetail.AUTH_FAILURE);
    }

    @Test
    @DisplayName("null detail is rejected with NullPointerException")
    void nullDetail_throws() {
        assertThatThrownBy(() -> valid(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("detail");
    }
}
