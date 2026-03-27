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
 * Tests for {@link DegradedEvent} — wrapper for events whose payload could not be upcast.
 */
@DisplayName("DegradedEvent")
class DegradedEventTest {

    // ── Construction ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {

        @Test
        @DisplayName("all 4 fields accessible after construction")
        void allFieldsAccessible() {
            var event = new DegradedEvent(
                    "device.state_changed", 2, "{\"key\":\"value\"}", "Missing required field");

            assertThat(event.eventType()).isEqualTo("device.state_changed");
            assertThat(event.schemaVersion()).isEqualTo(2);
            assertThat(event.rawPayload()).isEqualTo("{\"key\":\"value\"}");
            assertThat(event.failureReason()).isEqualTo("Missing required field");
        }

        @Test
        @DisplayName("implements DomainEvent")
        void implementsDomainEvent() {
            var event = new DegradedEvent("type", 1, "{}", "reason");
            assertThat(event).isInstanceOf(DomainEvent.class);
        }

        @Test
        @DisplayName("record has exactly 4 components")
        void exactlyFourFields() {
            assertThat(DegradedEvent.class.getRecordComponents()).hasSize(4);
        }
    }

    // ── Null validation ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Null validation")
    class NullValidationTests {

        @Test
        @DisplayName("null eventType throws NullPointerException")
        void nullEventType() {
            assertThatNullPointerException().isThrownBy(() ->
                    new DegradedEvent(null, 1, "{}", "reason"))
                    .withMessageContaining("eventType");
        }

        @Test
        @DisplayName("null rawPayload throws NullPointerException")
        void nullRawPayload() {
            assertThatNullPointerException().isThrownBy(() ->
                    new DegradedEvent("type", 1, null, "reason"))
                    .withMessageContaining("rawPayload");
        }

        @Test
        @DisplayName("null failureReason throws NullPointerException")
        void nullFailureReason() {
            assertThatNullPointerException().isThrownBy(() ->
                    new DegradedEvent("type", 1, "{}", null))
                    .withMessageContaining("failureReason");
        }
    }

    // ── Range validation ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Range validation")
    class RangeValidationTests {

        @Test
        @DisplayName("blank eventType throws IllegalArgumentException")
        void blankEventType() {
            assertThatThrownBy(() -> new DegradedEvent("  ", 1, "{}", "reason"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("blank");
        }

        @Test
        @DisplayName("schemaVersion < 1 throws IllegalArgumentException")
        void schemaVersionZero() {
            assertThatThrownBy(() -> new DegradedEvent("type", 0, "{}", "reason"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("schemaVersion");
        }
    }

    // ── Equals / hashCode ────────────────────────────────────────────────

    @Test
    @DisplayName("identical DegradedEvents are equal")
    void identicalEqual() {
        var a = new DegradedEvent("type", 1, "{}", "reason");
        var b = new DegradedEvent("type", 1, "{}", "reason");
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    @DisplayName("DegradedEvents with different fields are not equal")
    void differentNotEqual() {
        var a = new DegradedEvent("type.a", 1, "{}", "reason");
        var b = new DegradedEvent("type.b", 1, "{}", "reason");
        assertThat(a).isNotEqualTo(b);
    }
}
