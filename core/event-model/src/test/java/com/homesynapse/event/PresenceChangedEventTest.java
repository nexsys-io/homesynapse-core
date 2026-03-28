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
 * Tests for {@link PresenceChangedEvent} — event emitted when derived presence state
 * is updated by Presence Projection.
 */
@DisplayName("PresenceChangedEvent")
class PresenceChangedEventTest {

    // ── Construction ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {

        @Test
        @DisplayName("all 2 fields accessible after construction")
        void allFieldsAccessible() {
            var event = new PresenceChangedEvent("home", "away");

            assertThat(event.previousState()).isEqualTo("home");
            assertThat(event.newState()).isEqualTo("away");
        }

        @Test
        @DisplayName("implements DomainEvent")
        void implementsDomainEvent() {
            var event = new PresenceChangedEvent("away", "home");
            assertThat(event).isInstanceOf(DomainEvent.class);
        }

        @Test
        @DisplayName("record has exactly 2 components")
        void exactlyTwoFields() {
            assertThat(PresenceChangedEvent.class.getRecordComponents()).hasSize(2);
        }
    }

    // ── Null validation ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Null validation")
    class NullValidationTests {

        @Test
        @DisplayName("null previousState throws NullPointerException")
        void nullPreviousState() {
            assertThatNullPointerException().isThrownBy(() ->
                    new PresenceChangedEvent(null, "away"))
                    .withMessageContaining("previousState");
        }

        @Test
        @DisplayName("null newState throws NullPointerException")
        void nullNewState() {
            assertThatNullPointerException().isThrownBy(() ->
                    new PresenceChangedEvent("home", null))
                    .withMessageContaining("newState");
        }
    }

    // ── Blank validation ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Blank validation")
    class BlankValidationTests {

        @Test
        @DisplayName("blank previousState throws IllegalArgumentException")
        void blankPreviousState() {
            assertThatThrownBy(() -> new PresenceChangedEvent("  ", "away"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("blank");
        }

        @Test
        @DisplayName("blank newState throws IllegalArgumentException")
        void blankNewState() {
            assertThatThrownBy(() -> new PresenceChangedEvent("home", "  "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("blank");
        }
    }

    // ── Equals / hashCode ────────────────────────────────────────────────

    @Test
    @DisplayName("identical PresenceChangedEvents are equal")
    void identicalEqual() {
        var a = new PresenceChangedEvent("home", "away");
        var b = new PresenceChangedEvent("home", "away");
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    @DisplayName("PresenceChangedEvents with different fields are not equal")
    void differentNotEqual() {
        var a = new PresenceChangedEvent("home", "away");
        var b = new PresenceChangedEvent("home", "unknown");
        assertThat(a).isNotEqualTo(b);
    }
}
