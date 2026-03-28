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
 * Tests for {@link AvailabilityChangedEvent} — emitted when device availability status changes.
 */
@DisplayName("AvailabilityChangedEvent")
class AvailabilityChangedEventTest {

    // ── Construction ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {

        @Test
        @DisplayName("all 2 fields accessible after construction")
        void allFieldsAccessible() {
            var event = new AvailabilityChangedEvent("online", "offline");

            assertThat(event.previousStatus()).isEqualTo("online");
            assertThat(event.newStatus()).isEqualTo("offline");
        }

        @Test
        @DisplayName("implements DomainEvent")
        void implementsDomainEvent() {
            var event = new AvailabilityChangedEvent("online", "offline");
            assertThat(event).isInstanceOf(DomainEvent.class);
        }

        @Test
        @DisplayName("record has exactly 2 components")
        void exactlyTwoFields() {
            assertThat(AvailabilityChangedEvent.class.getRecordComponents()).hasSize(2);
        }
    }

    // ── Null validation ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Null validation")
    class NullValidationTests {

        @Test
        @DisplayName("null previousStatus throws NullPointerException")
        void nullPreviousStatus() {
            assertThatNullPointerException().isThrownBy(() ->
                    new AvailabilityChangedEvent(null, "offline"))
                    .withMessageContaining("previousStatus");
        }

        @Test
        @DisplayName("null newStatus throws NullPointerException")
        void nullNewStatus() {
            assertThatNullPointerException().isThrownBy(() ->
                    new AvailabilityChangedEvent("online", null))
                    .withMessageContaining("newStatus");
        }
    }

    // ── Range validation ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Range validation")
    class RangeValidationTests {

        @Test
        @DisplayName("blank previousStatus throws IllegalArgumentException")
        void blankPreviousStatus() {
            assertThatThrownBy(() -> new AvailabilityChangedEvent("  ", "offline"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("blank");
        }

        @Test
        @DisplayName("blank newStatus throws IllegalArgumentException")
        void blankNewStatus() {
            assertThatThrownBy(() -> new AvailabilityChangedEvent("online", "  "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("blank");
        }
    }

    // ── Equals / hashCode ────────────────────────────────────────────────

    @Test
    @DisplayName("identical AvailabilityChangedEvents are equal")
    void identicalEqual() {
        var a = new AvailabilityChangedEvent("online", "offline");
        var b = new AvailabilityChangedEvent("online", "offline");
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    @DisplayName("AvailabilityChangedEvents with different fields are not equal")
    void differentNotEqual() {
        var a = new AvailabilityChangedEvent("online", "offline");
        var b = new AvailabilityChangedEvent("offline", "online");
        assertThat(a).isNotEqualTo(b);
    }
}
