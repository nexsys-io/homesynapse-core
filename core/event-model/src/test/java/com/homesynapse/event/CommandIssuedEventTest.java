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
 * Tests for {@link CommandIssuedEvent} — event published when a command is issued to a target entity.
 */
@DisplayName("CommandIssuedEvent")
class CommandIssuedEventTest {

    // ── Construction ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {

        @Test
        @DisplayName("all 5 fields accessible after construction")
        void allFieldsAccessible() {
            var targetRef = new Ulid(1L, 1L);
            var event = new CommandIssuedEvent(
                    targetRef,
                    "set_level",
                    "{\"level\": 50}",
                    5000,
                    CommandIdempotency.IDEMPOTENT);

            assertThat(event.targetEntityRef()).isEqualTo(targetRef);
            assertThat(event.commandType()).isEqualTo("set_level");
            assertThat(event.parameters()).isEqualTo("{\"level\": 50}");
            assertThat(event.confirmationTimeoutMs()).isEqualTo(5000);
            assertThat(event.idempotencyClass()).isEqualTo(CommandIdempotency.IDEMPOTENT);
        }

        @Test
        @DisplayName("implements DomainEvent")
        void implementsDomainEvent() {
            var event = new CommandIssuedEvent(
                    new Ulid(1L, 1L),
                    "toggle",
                    "{}",
                    1000,
                    CommandIdempotency.NOT_IDEMPOTENT);
            assertThat(event).isInstanceOf(DomainEvent.class);
        }

        @Test
        @DisplayName("record has exactly 5 components")
        void exactlyFiveFields() {
            assertThat(CommandIssuedEvent.class.getRecordComponents()).hasSize(5);
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
                    new CommandIssuedEvent(
                            null,
                            "toggle",
                            "{}",
                            1000,
                            CommandIdempotency.IDEMPOTENT))
                    .withMessageContaining("targetEntityRef");
        }

        @Test
        @DisplayName("null commandType throws NullPointerException")
        void nullCommandType() {
            assertThatNullPointerException().isThrownBy(() ->
                    new CommandIssuedEvent(
                            new Ulid(1L, 1L),
                            null,
                            "{}",
                            1000,
                            CommandIdempotency.IDEMPOTENT))
                    .withMessageContaining("commandType");
        }

        @Test
        @DisplayName("null parameters throws NullPointerException")
        void nullParameters() {
            assertThatNullPointerException().isThrownBy(() ->
                    new CommandIssuedEvent(
                            new Ulid(1L, 1L),
                            "toggle",
                            null,
                            1000,
                            CommandIdempotency.IDEMPOTENT))
                    .withMessageContaining("parameters");
        }

        @Test
        @DisplayName("null idempotencyClass throws NullPointerException")
        void nullIdempotencyClass() {
            assertThatNullPointerException().isThrownBy(() ->
                    new CommandIssuedEvent(
                            new Ulid(1L, 1L),
                            "toggle",
                            "{}",
                            1000,
                            null))
                    .withMessageContaining("idempotencyClass");
        }
    }

    // ── Range validation ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Range validation")
    class RangeValidationTests {

        @Test
        @DisplayName("blank commandType throws IllegalArgumentException")
        void blankCommandType() {
            assertThatThrownBy(() -> new CommandIssuedEvent(
                    new Ulid(1L, 1L),
                    "  ",
                    "{}",
                    1000,
                    CommandIdempotency.IDEMPOTENT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("commandType").hasMessageContaining("blank");
        }

        @Test
        @DisplayName("blank parameters throws IllegalArgumentException")
        void blankParameters() {
            assertThatThrownBy(() -> new CommandIssuedEvent(
                    new Ulid(1L, 1L),
                    "toggle",
                    "  ",
                    1000,
                    CommandIdempotency.IDEMPOTENT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("parameters").hasMessageContaining("blank");
        }

        @Test
        @DisplayName("confirmationTimeoutMs <= 0 throws IllegalArgumentException")
        void zeroConfirmationTimeoutMs() {
            assertThatThrownBy(() -> new CommandIssuedEvent(
                    new Ulid(1L, 1L),
                    "toggle",
                    "{}",
                    0,
                    CommandIdempotency.IDEMPOTENT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("confirmationTimeoutMs");
        }

        @Test
        @DisplayName("negative confirmationTimeoutMs throws IllegalArgumentException")
        void negativeConfirmationTimeoutMs() {
            assertThatThrownBy(() -> new CommandIssuedEvent(
                    new Ulid(1L, 1L),
                    "toggle",
                    "{}",
                    -1,
                    CommandIdempotency.IDEMPOTENT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("confirmationTimeoutMs");
        }
    }

    // ── Equals / hashCode ────────────────────────────────────────────────

    @Test
    @DisplayName("identical CommandIssuedEvents are equal")
    void identicalEqual() {
        var a = new CommandIssuedEvent(
                new Ulid(1L, 1L),
                "toggle",
                "{}",
                1000,
                CommandIdempotency.IDEMPOTENT);
        var b = new CommandIssuedEvent(
                new Ulid(1L, 1L),
                "toggle",
                "{}",
                1000,
                CommandIdempotency.IDEMPOTENT);
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    @DisplayName("CommandIssuedEvents with different fields are not equal")
    void differentNotEqual() {
        var a = new CommandIssuedEvent(
                new Ulid(1L, 1L),
                "toggle",
                "{}",
                1000,
                CommandIdempotency.IDEMPOTENT);
        var b = new CommandIssuedEvent(
                new Ulid(1L, 1L),
                "set_level",
                "{}",
                1000,
                CommandIdempotency.IDEMPOTENT);
        assertThat(a).isNotEqualTo(b);
    }
}
