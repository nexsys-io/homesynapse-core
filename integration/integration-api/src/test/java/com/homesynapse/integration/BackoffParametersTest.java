/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

/**
 * Verifies the {@link BackoffParameters} guards and the {@code defaults()}
 * schedule (AMD-62). {@code defaults()} must reproduce the empirically-derived
 * Home Assistant schedule 5/10/20/40/80/80&hellip; seconds under iterated
 * application capped at {@code maxDelay}.
 */
@DisplayName("BackoffParameters")
class BackoffParametersTest {

    /** Default constructor required by JUnit 5 under {@code -Xlint:all -Werror}. */
    BackoffParametersTest() {
        // Defaults are sufficient.
    }

    /**
     * The retry delay for a given attempt: {@code initialDelay * multiplier^attempt},
     * capped at {@code maxDelay}. This is the pure schedule function the M9
     * supervisor will apply (AMD-62-INV-01); the test derives it here to pin the
     * contract that {@code defaults()} encodes.
     */
    private static Duration delayForAttempt(BackoffParameters p, int attempt) {
        double millis = p.initialDelay().toMillis() * Math.pow(p.multiplier(), attempt);
        long capped = Math.min((long) millis, p.maxDelay().toMillis());
        return Duration.ofMillis(capped);
    }

    @Test
    @DisplayName("defaults() exposes initialDelay=5s, multiplier=2.0, maxDelay=80s")
    void defaults_values() {
        BackoffParameters p = BackoffParameters.defaults();

        assertThat(p.initialDelay()).isEqualTo(Duration.ofSeconds(5));
        assertThat(p.multiplier()).isEqualTo(2.0);
        assertThat(p.maxDelay()).isEqualTo(Duration.ofSeconds(80));
    }

    @Test
    @DisplayName("defaults() reproduce the 5/10/20/40/80/80 schedule under iteration")
    void defaults_produceHaSchedule() {
        BackoffParameters p = BackoffParameters.defaults();

        assertThat(delayForAttempt(p, 0)).isEqualTo(Duration.ofSeconds(5));
        assertThat(delayForAttempt(p, 1)).isEqualTo(Duration.ofSeconds(10));
        assertThat(delayForAttempt(p, 2)).isEqualTo(Duration.ofSeconds(20));
        assertThat(delayForAttempt(p, 3)).isEqualTo(Duration.ofSeconds(40));
        assertThat(delayForAttempt(p, 4)).isEqualTo(Duration.ofSeconds(80));
        assertThat(delayForAttempt(p, 5))
                .as("attempt 5 is capped at maxDelay")
                .isEqualTo(Duration.ofSeconds(80));
        assertThat(delayForAttempt(p, 10))
                .as("the cap holds for all further attempts")
                .isEqualTo(Duration.ofSeconds(80));
    }

    @Test
    @DisplayName("zero initialDelay is rejected")
    void initialDelayZero_throws() {
        assertThatThrownBy(() ->
                new BackoffParameters(Duration.ZERO, 2.0, Duration.ofSeconds(80)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("initialDelay");
    }

    @Test
    @DisplayName("negative initialDelay is rejected")
    void initialDelayNegative_throws() {
        assertThatThrownBy(() ->
                new BackoffParameters(Duration.ofSeconds(-1), 2.0, Duration.ofSeconds(80)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("initialDelay");
    }

    @Test
    @DisplayName("multiplier below 1.0 is rejected")
    void multiplierBelowOne_throws() {
        assertThatThrownBy(() ->
                new BackoffParameters(Duration.ofSeconds(5), 0.5, Duration.ofSeconds(80)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("multiplier");
    }

    @Test
    @DisplayName("maxDelay less than initialDelay is rejected")
    void maxDelayLessThanInitial_throws() {
        assertThatThrownBy(() ->
                new BackoffParameters(Duration.ofSeconds(80), 2.0, Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxDelay");
    }

    @Test
    @DisplayName("null delays are rejected with NullPointerException")
    void nullDelays_throw() {
        assertThatThrownBy(() ->
                new BackoffParameters(null, 2.0, Duration.ofSeconds(80)))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("initialDelay");
        assertThatThrownBy(() ->
                new BackoffParameters(Duration.ofSeconds(5), 2.0, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("maxDelay");
    }

    @Test
    @DisplayName("boundary values are accepted: multiplier == 1.0 and maxDelay == initialDelay")
    void boundaryValues_accepted() {
        Throwable thrown = catchThrowable(() ->
                new BackoffParameters(Duration.ofSeconds(5), 1.0, Duration.ofSeconds(5)));

        assertThat(thrown).as("multiplier 1.0 and maxDelay == initialDelay are legal").isNull();
    }
}
