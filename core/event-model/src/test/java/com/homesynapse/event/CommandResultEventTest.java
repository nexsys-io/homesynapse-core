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
 * Tests for {@link CommandResultEvent} — event published when a command outcome is reported by the protocol layer.
 */
@DisplayName("CommandResultEvent")
class CommandResultEventTest {

    // ── Construction ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {

        @Test
        @DisplayName("all 4 fields accessible after construction with non-null failureReason")
        void allFieldsAccessibleWithFailureReason() {
            var targetRef = new Ulid(1L, 1L);
            var event = new CommandResultEvent(
                    targetRef,
                    "set_level",
                    "rejected",
                    "Device unreachable");

            assertThat(event.targetEntityRef()).isEqualTo(targetRef);
            assertThat(event.commandType()).isEqualTo("set_level");
            assertThat(event.outcome()).isEqualTo("rejected");
            assertThat(event.failureReason()).isEqualTo("Device unreachable");
        }

        @Test
        @DisplayName("all 4 fields accessible after construction with null failureReason")
        void allFieldsAccessibleWithNullFailureReason() {
            var targetRef = new Ulid(1L, 1L);
            var event = new CommandResultEvent(
                    targetRef,
                    "toggle",
                    "acknowledged",
                    null);

            assertThat(event.targetEntityRef()).isEqualTo(targetRef);
            assertThat(event.commandType()).isEqualTo("toggle");
            assertThat(event.outcome()).isEqualTo("acknowledged");
            assertThat(event.failureReason()).isNull();
        }

        @Test
        @DisplayName("implements DomainEvent")
        void implementsDomainEvent() {
            var event = new CommandResultEvent(
                    new Ulid(1L, 1L),
                    "toggle",
                    "acknowledged",
                    null);
            assertThat(event).isInstanceOf(DomainEvent.class);
        }

        @Test
        @DisplayName("record has exactly 4 components")
        void exactlyFourFields() {
            assertThat(CommandResultEvent.class.getRecordComponents()).hasSize(4);
        }
    }

    // ── Null validation ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Null validation")
    class NullValidationTests {

        @Test
        @DisplayName("null targetEntityRef throws NullPointerException")
        void nullTargetEntityRef() {
            assertThatNullPointerException().isThrownBy(() ->
                    new CommandResultEvent(
                            null,
                            "toggle",
                            "acknowledged",
                            null))
                    .withMessageContaining("targetEntityRef");
        }

        @Test
        @DisplayName("null commandType throws NullPointerException")
        void nullCommandType() {
            assertThatNullPointerException().isThrownBy(() ->
                    new CommandResultEvent(
                            new Ulid(1L, 1L),
                            null,
                            "acknowledged",
                            null))
                    .withMessageContaining("commandType");
        }

        @Test
        @DisplayName("null outcome throws NullPointerException")
        void nullOutcome() {
            assertThatNullPointerException().isThrownBy(() ->
                    new CommandResultEvent(
                            new Ulid(1L, 1L),
                            "toggle",
                            null,
                            null))
                    .withMessageContaining("outcome");
        }
    }

    // ── Range validation ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Range validation")
    class RangeValidationTests {

        @Test
        @DisplayName("blank commandType throws IllegalArgumentException")
        void blankCommandType() {
            assertThatThrownBy(() -> new CommandResultEvent(
                    new Ulid(1L, 1L),
                    "  ",
                    "acknowledged",
                    null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("commandType").hasMessageContaining("blank");
        }

        @Test
        @DisplayName("blank outcome throws IllegalArgumentException")
        void blankOutcome() {
            assertThatThrownBy(() -> new CommandResultEvent(
                    new Ulid(1L, 1L),
                    "toggle",
                    "  ",
                    null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("outcome").hasMessageContaining("blank");
        }
    }

    // ── Equals / hashCode ────────────────────────────────────────────────

    @Test
    @DisplayName("identical CommandResultEvents are equal")
    void identicalEqual() {
        var a = new CommandResultEvent(
                new Ulid(1L, 1L),
                "toggle",
                "acknowledged",
                null);
        var b = new CommandResultEvent(
                new Ulid(1L, 1L),
                "toggle",
                "acknowledged",
                null);
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    @DisplayName("CommandResultEvents with different fields are not equal")
    void differentNotEqual() {
        var a = new CommandResultEvent(
                new Ulid(1L, 1L),
                "toggle",
                "acknowledged",
                null);
        var b = new CommandResultEvent(
                new Ulid(1L, 1L),
                "toggle",
                "rejected",
                "Device unreachable");
        assertThat(a).isNotEqualTo(b);
    }
}
