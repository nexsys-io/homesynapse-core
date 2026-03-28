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
 * Tests for {@link AutomationCompletedEvent} — event emitted when an automation run
 * reaches a terminal state.
 */
@DisplayName("AutomationCompletedEvent")
class AutomationCompletedEventTest {

    // ── Construction ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {

        @Test
        @DisplayName("all 3 fields accessible with non-null failureReason")
        void allFieldsAccessibleWithFailureReason() {
            var event = new AutomationCompletedEvent("failure", "timeout occurred", 5000L);

            assertThat(event.status()).isEqualTo("failure");
            assertThat(event.failureReason()).isEqualTo("timeout occurred");
            assertThat(event.durationMs()).isEqualTo(5000L);
        }

        @Test
        @DisplayName("all 3 fields accessible with null failureReason")
        void allFieldsAccessibleWithNullFailureReason() {
            var event = new AutomationCompletedEvent("success", null, 1000L);

            assertThat(event.status()).isEqualTo("success");
            assertThat(event.failureReason()).isNull();
            assertThat(event.durationMs()).isEqualTo(1000L);
        }

        @Test
        @DisplayName("implements DomainEvent")
        void implementsDomainEvent() {
            var event = new AutomationCompletedEvent("success", null, 1000L);
            assertThat(event).isInstanceOf(DomainEvent.class);
        }

        @Test
        @DisplayName("record has exactly 3 components")
        void exactlyThreeFields() {
            assertThat(AutomationCompletedEvent.class.getRecordComponents()).hasSize(3);
        }
    }

    // ── Null validation ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Null validation")
    class NullValidationTests {

        @Test
        @DisplayName("null status throws NullPointerException")
        void nullStatus() {
            assertThatNullPointerException().isThrownBy(() ->
                    new AutomationCompletedEvent(null, "reason", 1000L))
                    .withMessageContaining("status");
        }
    }

    // ── Range validation ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Range validation")
    class RangeValidationTests {

        @Test
        @DisplayName("blank status throws IllegalArgumentException")
        void blankStatus() {
            assertThatThrownBy(() -> new AutomationCompletedEvent("  ", "reason", 1000L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("blank");
        }

        @Test
        @DisplayName("negative durationMs throws IllegalArgumentException")
        void negativeDurationMs() {
            assertThatThrownBy(() -> new AutomationCompletedEvent("success", null, -1L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("durationMs");
        }

        @Test
        @DisplayName("zero durationMs is valid")
        void zeroDurationMs() {
            var event = new AutomationCompletedEvent("success", null, 0L);
            assertThat(event.durationMs()).isZero();
        }
    }

    // ── Equals / hashCode ────────────────────────────────────────────────

    @Test
    @DisplayName("identical AutomationCompletedEvents are equal")
    void identicalEqual() {
        var a = new AutomationCompletedEvent("success", null, 1000L);
        var b = new AutomationCompletedEvent("success", null, 1000L);
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    @DisplayName("AutomationCompletedEvents with different fields are not equal")
    void differentNotEqual() {
        var a = new AutomationCompletedEvent("success", null, 1000L);
        var b = new AutomationCompletedEvent("failure", "error", 1000L);
        assertThat(a).isNotEqualTo(b);
    }
}
