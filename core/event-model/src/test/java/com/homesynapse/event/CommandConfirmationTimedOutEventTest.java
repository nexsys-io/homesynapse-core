/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CommandConfirmationTimedOutEvent} — event published when command confirmation times out.
 */
@DisplayName("CommandConfirmationTimedOutEvent")
class CommandConfirmationTimedOutEventTest {

    // ── Construction ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {

        @Test
        @DisplayName("all 2 fields accessible after construction with non-null resultEventId")
        void allFieldsAccessibleWithResultEventId() {
            var commandId = EventId.of(new com.homesynapse.platform.identity.Ulid(1L, 1L));
            var resultId = EventId.of(new com.homesynapse.platform.identity.Ulid(2L, 2L));
            var event = new CommandConfirmationTimedOutEvent(commandId, resultId);

            assertThat(event.commandEventId()).isEqualTo(commandId);
            assertThat(event.resultEventId()).isEqualTo(resultId);
        }

        @Test
        @DisplayName("all 2 fields accessible after construction with null resultEventId")
        void allFieldsAccessibleWithNullResultEventId() {
            var commandId = EventId.of(new com.homesynapse.platform.identity.Ulid(1L, 1L));
            var event = new CommandConfirmationTimedOutEvent(commandId, null);

            assertThat(event.commandEventId()).isEqualTo(commandId);
            assertThat(event.resultEventId()).isNull();
        }

        @Test
        @DisplayName("implements DomainEvent")
        void implementsDomainEvent() {
            var event = new CommandConfirmationTimedOutEvent(
                    EventId.of(new com.homesynapse.platform.identity.Ulid(1L, 1L)),
                    null);
            assertThat(event).isInstanceOf(DomainEvent.class);
        }

        @Test
        @DisplayName("record has exactly 2 components")
        void exactlyTwoFields() {
            assertThat(CommandConfirmationTimedOutEvent.class.getRecordComponents()).hasSize(2);
        }
    }

    // ── Null validation ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Null validation")
    class NullValidationTests {

        @Test
        @DisplayName("null commandEventId throws NullPointerException")
        void nullCommandEventId() {
            assertThatNullPointerException().isThrownBy(() ->
                    new CommandConfirmationTimedOutEvent(
                            null,
                            EventId.of(new com.homesynapse.platform.identity.Ulid(2L, 2L))))
                    .withMessageContaining("commandEventId");
        }
    }

    // ── Equals / hashCode ────────────────────────────────────────────────

    @Test
    @DisplayName("identical CommandConfirmationTimedOutEvents are equal")
    void identicalEqual() {
        var commandId = EventId.of(new com.homesynapse.platform.identity.Ulid(1L, 1L));
        var resultId = EventId.of(new com.homesynapse.platform.identity.Ulid(2L, 2L));
        var a = new CommandConfirmationTimedOutEvent(commandId, resultId);
        var b = new CommandConfirmationTimedOutEvent(commandId, resultId);
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    @DisplayName("CommandConfirmationTimedOutEvents with different fields are not equal")
    void differentNotEqual() {
        var commandId = EventId.of(new com.homesynapse.platform.identity.Ulid(1L, 1L));
        var resultId1 = EventId.of(new com.homesynapse.platform.identity.Ulid(2L, 2L));
        var resultId2 = EventId.of(new com.homesynapse.platform.identity.Ulid(3L, 3L));
        var a = new CommandConfirmationTimedOutEvent(commandId, resultId1);
        var b = new CommandConfirmationTimedOutEvent(commandId, resultId2);
        assertThat(a).isNotEqualTo(b);
    }
}
