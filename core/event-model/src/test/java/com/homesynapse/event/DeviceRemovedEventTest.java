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
 * Tests for {@link DeviceRemovedEvent} — emitted when a device is removed from the system.
 */
@DisplayName("DeviceRemovedEvent")
class DeviceRemovedEventTest {

    // ── Construction ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {

        @Test
        @DisplayName("all 1 field accessible after construction")
        void allFieldsAccessible() {
            var event = new DeviceRemovedEvent("Device unresponsive");

            assertThat(event.reason()).isEqualTo("Device unresponsive");
        }

        @Test
        @DisplayName("implements DomainEvent")
        void implementsDomainEvent() {
            var event = new DeviceRemovedEvent("Device unresponsive");
            assertThat(event).isInstanceOf(DomainEvent.class);
        }

        @Test
        @DisplayName("record has exactly 1 component")
        void exactlyOneField() {
            assertThat(DeviceRemovedEvent.class.getRecordComponents()).hasSize(1);
        }
    }

    // ── Null validation ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Null validation")
    class NullValidationTests {

        @Test
        @DisplayName("null reason throws NullPointerException")
        void nullReason() {
            assertThatNullPointerException().isThrownBy(() ->
                    new DeviceRemovedEvent(null))
                    .withMessageContaining("reason");
        }
    }

    // ── Range validation ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Range validation")
    class RangeValidationTests {

        @Test
        @DisplayName("blank reason throws IllegalArgumentException")
        void blankReason() {
            assertThatThrownBy(() -> new DeviceRemovedEvent("  "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("blank");
        }
    }

    // ── Equals / hashCode ────────────────────────────────────────────────

    @Test
    @DisplayName("identical DeviceRemovedEvents are equal")
    void identicalEqual() {
        var a = new DeviceRemovedEvent("Device unresponsive");
        var b = new DeviceRemovedEvent("Device unresponsive");
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    @DisplayName("DeviceRemovedEvents with different fields are not equal")
    void differentNotEqual() {
        var a = new DeviceRemovedEvent("Device unresponsive");
        var b = new DeviceRemovedEvent("User requested removal");
        assertThat(a).isNotEqualTo(b);
    }
}
