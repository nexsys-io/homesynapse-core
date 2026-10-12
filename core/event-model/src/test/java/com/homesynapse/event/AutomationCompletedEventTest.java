/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.homesynapse.platform.identity.Ulid;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link AutomationCompletedEvent} — the AMD-92 row-2 reshape (7 flattened
 * components) emitted when a Run reaches a terminal state, plus J3 T-B's eighth component
 * {@code skippedUnavailable} (IR-95; additive — a pre-J3 row reads 0).
 */
@DisplayName("AutomationCompletedEvent")
class AutomationCompletedEventTest {

    private static final Ulid RUN = Ulid.parse("00000000000000000000000000");

    // ── Construction ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {

        @Test
        @DisplayName("all 8 fields accessible with non-null reasons")
        void allFieldsAccessibleWithReasons() {
            var event = new AutomationCompletedEvent(
                    RUN, "FAILED", 5000L, 3, 2, "timeout occurred", "restart_mode", 4);

            assertThat(event.runId()).isEqualTo(RUN);
            assertThat(event.finalStatus()).isEqualTo("FAILED");
            assertThat(event.durationMs()).isEqualTo(5000L);
            assertThat(event.actionCount()).isEqualTo(3);
            assertThat(event.commandCount()).isEqualTo(2);
            assertThat(event.failureReason()).isEqualTo("timeout occurred");
            assertThat(event.abortReason()).isEqualTo("restart_mode");
            assertThat(event.skippedUnavailable()).isEqualTo(4);
        }

        @Test
        @DisplayName("nullable reasons may both be null")
        void nullableReasons() {
            var event = new AutomationCompletedEvent(RUN, "COMPLETED", 1000L, 1, 0, null, null, 0);

            assertThat(event.failureReason()).isNull();
            assertThat(event.abortReason()).isNull();
        }

        @Test
        @DisplayName("implements DomainEvent")
        void implementsDomainEvent() {
            var event = new AutomationCompletedEvent(RUN, "COMPLETED", 1000L, 0, 0, null, null, 0);
            assertThat(event).isInstanceOf(DomainEvent.class);
        }

        @Test
        @DisplayName("record has exactly 8 components (J3 T-B: skippedUnavailable is the eighth, "
                + "appended LAST)")
        void exactlyEightFields() {
            var components = AutomationCompletedEvent.class.getRecordComponents();
            assertThat(components).hasSize(8);
            assertThat(components[7].getName()).isEqualTo("skippedUnavailable");
        }

        @Test
        @DisplayName("carries the AUTOMATION_COMPLETED @EventType")
        void eventTypeAnnotation() {
            assertThat(AutomationCompletedEvent.class.getAnnotation(EventType.class).value())
                    .isEqualTo(EventTypes.AUTOMATION_COMPLETED);
        }
    }

    // ── Null validation ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Null validation")
    class NullValidationTests {

        @Test
        @DisplayName("null runId throws NullPointerException")
        void nullRunId() {
            assertThatNullPointerException().isThrownBy(() ->
                    new AutomationCompletedEvent(null, "COMPLETED", 1000L, 0, 0, null, null, 0))
                    .withMessageContaining("runId");
        }

        @Test
        @DisplayName("null finalStatus throws NullPointerException")
        void nullFinalStatus() {
            assertThatNullPointerException().isThrownBy(() ->
                    new AutomationCompletedEvent(RUN, null, 1000L, 0, 0, null, null, 0))
                    .withMessageContaining("finalStatus");
        }
    }

    // ── Range validation ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Range validation")
    class RangeValidationTests {

        @Test
        @DisplayName("blank finalStatus throws IllegalArgumentException")
        void blankFinalStatus() {
            assertThatThrownBy(() ->
                    new AutomationCompletedEvent(RUN, "  ", 1000L, 0, 0, null, null, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("finalStatus");
        }

        @Test
        @DisplayName("negative durationMs throws IllegalArgumentException")
        void negativeDurationMs() {
            assertThatThrownBy(() ->
                    new AutomationCompletedEvent(RUN, "COMPLETED", -1L, 0, 0, null, null, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("durationMs");
        }

        @Test
        @DisplayName("negative actionCount throws IllegalArgumentException")
        void negativeActionCount() {
            assertThatThrownBy(() ->
                    new AutomationCompletedEvent(RUN, "COMPLETED", 0L, -1, 0, null, null, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("actionCount");
        }

        @Test
        @DisplayName("negative commandCount throws IllegalArgumentException")
        void negativeCommandCount() {
            assertThatThrownBy(() ->
                    new AutomationCompletedEvent(RUN, "COMPLETED", 0L, 0, -1, null, null, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("commandCount");
        }

        @Test
        @DisplayName("J3 E-1: negative skippedUnavailable throws IllegalArgumentException naming it")
        void negativeSkippedUnavailable() {
            assertThatThrownBy(() ->
                    new AutomationCompletedEvent(RUN, "COMPLETED", 0L, 0, 0, null, null, -1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("skippedUnavailable");
        }

        @Test
        @DisplayName("zero durationMs and counts are valid")
        void zeroValues() {
            var event = new AutomationCompletedEvent(RUN, "COMPLETED", 0L, 0, 0, null, null, 0);
            assertThat(event.durationMs()).isZero();
            assertThat(event.actionCount()).isZero();
            assertThat(event.commandCount()).isZero();
            assertThat(event.skippedUnavailable()).isZero();
        }
    }

    // ── Equals / hashCode ────────────────────────────────────────────────

    @Test
    @DisplayName("identical AutomationCompletedEvents are equal")
    void identicalEqual() {
        var a = new AutomationCompletedEvent(RUN, "COMPLETED", 1000L, 1, 1, null, null, 0);
        var b = new AutomationCompletedEvent(RUN, "COMPLETED", 1000L, 1, 1, null, null, 0);
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    @DisplayName("AutomationCompletedEvents with different fields are not equal")
    void differentNotEqual() {
        var a = new AutomationCompletedEvent(RUN, "COMPLETED", 1000L, 1, 1, null, null, 0);
        var b = new AutomationCompletedEvent(RUN, "FAILED", 1000L, 1, 1, "error", null, 0);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("J3 E-2: two events differing only in skippedUnavailable are not equal")
    void differentSkippedUnavailableNotEqual() {
        var a = new AutomationCompletedEvent(RUN, "COMPLETED", 1000L, 1, 0, null, null, 0);
        var b = new AutomationCompletedEvent(RUN, "COMPLETED", 1000L, 1, 0, null, null, 1);
        assertThat(a).isNotEqualTo(b);
        assertThat(a.hashCode()).isNotEqualTo(b.hashCode());
    }
}
