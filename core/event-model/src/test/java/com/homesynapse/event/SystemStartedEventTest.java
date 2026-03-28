/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SystemStartedEvent} — event emitted when the HomeSynapse process
 * starts.
 */
@DisplayName("SystemStartedEvent")
class SystemStartedEventTest {

    // ── Construction ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {

        @Test
        @DisplayName("all 2 fields accessible after construction")
        void allFieldsAccessible() {
            var event = new SystemStartedEvent("1.2.3", 5000L);

            assertThat(event.version()).isEqualTo("1.2.3");
            assertThat(event.startupDurationMs()).isEqualTo(5000L);
        }

        @Test
        @DisplayName("implements DomainEvent")
        void implementsDomainEvent() {
            var event = new SystemStartedEvent("1.0.0", 1000L);
            assertThat(event).isInstanceOf(DomainEvent.class);
        }

        @Test
        @DisplayName("record has exactly 2 components")
        void exactlyTwoFields() {
            assertThat(SystemStartedEvent.class.getRecordComponents()).hasSize(2);
        }
    }

    // ── Null validation ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Null validation")
    class NullValidationTests {

        @Test
        @DisplayName("null version throws NullPointerException")
        void nullVersion() {
            assertThatNullPointerException().isThrownBy(() ->
                    new SystemStartedEvent(null, 1000L))
                    .withMessageContaining("version");
        }
    }

    // ── Range validation ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Range validation")
    class RangeValidationTests {

        @Test
        @DisplayName("blank version throws IllegalArgumentException")
        void blankVersion() {
            assertThatThrownBy(() -> new SystemStartedEvent("  ", 1000L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("blank");
        }

        @Test
        @DisplayName("negative startupDurationMs throws IllegalArgumentException")
        void negativeStartupDurationMs() {
            assertThatThrownBy(() -> new SystemStartedEvent("1.0.0", -1L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("startupDurationMs");
        }

        @Test
        @DisplayName("zero startupDurationMs is valid")
        void zeroStartupDurationMs() {
            var event = new SystemStartedEvent("1.0.0", 0L);
            assertThat(event.startupDurationMs()).isZero();
        }
    }

    // ── Equals / hashCode ────────────────────────────────────────────────

    @Test
    @DisplayName("identical SystemStartedEvents are equal")
    void identicalEqual() {
        var a = new SystemStartedEvent("1.0.0", 1000L);
        var b = new SystemStartedEvent("1.0.0", 1000L);
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    @DisplayName("SystemStartedEvents with different fields are not equal")
    void differentNotEqual() {
        var a = new SystemStartedEvent("1.0.0", 1000L);
        var b = new SystemStartedEvent("1.1.0", 1000L);
        assertThat(a).isNotEqualTo(b);
    }
}
