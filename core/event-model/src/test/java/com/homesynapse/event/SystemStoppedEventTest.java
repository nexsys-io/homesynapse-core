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
 * Tests for {@link SystemStoppedEvent} — event emitted when the HomeSynapse process
 * shuts down.
 */
@DisplayName("SystemStoppedEvent")
class SystemStoppedEventTest {

    // ── Construction ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {

        @Test
        @DisplayName("all 2 fields accessible with cleanShutdown=true")
        void allFieldsAccessibleCleanTrue() {
            var event = new SystemStoppedEvent("graceful shutdown", true);

            assertThat(event.reason()).isEqualTo("graceful shutdown");
            assertThat(event.cleanShutdown()).isTrue();
        }

        @Test
        @DisplayName("all 2 fields accessible with cleanShutdown=false")
        void allFieldsAccessibleCleanFalse() {
            var event = new SystemStoppedEvent("crash detected", false);

            assertThat(event.reason()).isEqualTo("crash detected");
            assertThat(event.cleanShutdown()).isFalse();
        }

        @Test
        @DisplayName("implements DomainEvent")
        void implementsDomainEvent() {
            var event = new SystemStoppedEvent("shutdown", true);
            assertThat(event).isInstanceOf(DomainEvent.class);
        }

        @Test
        @DisplayName("record has exactly 2 components")
        void exactlyTwoFields() {
            assertThat(SystemStoppedEvent.class.getRecordComponents()).hasSize(2);
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
                    new SystemStoppedEvent(null, true))
                    .withMessageContaining("reason");
        }
    }

    // ── Blank validation ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Blank validation")
    class BlankValidationTests {

        @Test
        @DisplayName("blank reason throws IllegalArgumentException")
        void blankReason() {
            assertThatThrownBy(() -> new SystemStoppedEvent("  ", true))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("blank");
        }
    }

    // ── Equals / hashCode ────────────────────────────────────────────────

    @Test
    @DisplayName("identical SystemStoppedEvents are equal")
    void identicalEqual() {
        var a = new SystemStoppedEvent("shutdown", true);
        var b = new SystemStoppedEvent("shutdown", true);
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    @DisplayName("SystemStoppedEvents with different fields are not equal")
    void differentNotEqual() {
        var a = new SystemStoppedEvent("graceful", true);
        var b = new SystemStoppedEvent("crash", true);
        assertThat(a).isNotEqualTo(b);
    }
}
