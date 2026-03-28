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
 * Tests for {@link AutomationTriggeredEvent} — event emitted when an automation trigger
 * condition is met and a run begins.
 */
@DisplayName("AutomationTriggeredEvent")
class AutomationTriggeredEventTest {

    // ── Construction ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {

        @Test
        @DisplayName("all 2 fields accessible after construction")
        void allFieldsAccessible() {
            var event = new AutomationTriggeredEvent("motion_detected", "{\"sensor\":\"hall\"}");

            assertThat(event.triggerType()).isEqualTo("motion_detected");
            assertThat(event.triggerDetail()).isEqualTo("{\"sensor\":\"hall\"}");
        }

        @Test
        @DisplayName("implements DomainEvent")
        void implementsDomainEvent() {
            var event = new AutomationTriggeredEvent("motion", "{}");
            assertThat(event).isInstanceOf(DomainEvent.class);
        }

        @Test
        @DisplayName("record has exactly 2 components")
        void exactlyTwoFields() {
            assertThat(AutomationTriggeredEvent.class.getRecordComponents()).hasSize(2);
        }
    }

    // ── Null validation ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Null validation")
    class NullValidationTests {

        @Test
        @DisplayName("null triggerType throws NullPointerException")
        void nullTriggerType() {
            assertThatNullPointerException().isThrownBy(() ->
                    new AutomationTriggeredEvent(null, "{}"))
                    .withMessageContaining("triggerType");
        }

        @Test
        @DisplayName("null triggerDetail throws NullPointerException")
        void nullTriggerDetail() {
            assertThatNullPointerException().isThrownBy(() ->
                    new AutomationTriggeredEvent("motion", null))
                    .withMessageContaining("triggerDetail");
        }
    }

    // ── Blank validation ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Blank validation")
    class BlankValidationTests {

        @Test
        @DisplayName("blank triggerType throws IllegalArgumentException")
        void blankTriggerType() {
            assertThatThrownBy(() -> new AutomationTriggeredEvent("  ", "{}"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("blank");
        }
    }

    // ── Equals / hashCode ────────────────────────────────────────────────

    @Test
    @DisplayName("identical AutomationTriggeredEvents are equal")
    void identicalEqual() {
        var a = new AutomationTriggeredEvent("motion", "{}");
        var b = new AutomationTriggeredEvent("motion", "{}");
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    @DisplayName("AutomationTriggeredEvents with different fields are not equal")
    void differentNotEqual() {
        var a = new AutomationTriggeredEvent("motion", "{}");
        var b = new AutomationTriggeredEvent("timer", "{}");
        assertThat(a).isNotEqualTo(b);
    }
}
