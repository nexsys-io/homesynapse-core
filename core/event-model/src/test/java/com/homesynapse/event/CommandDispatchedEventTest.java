/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.homesynapse.platform.identity.Ulid;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CommandDispatchedEvent} — event published when a command is dispatched to an integration adapter.
 */
@DisplayName("CommandDispatchedEvent")
class CommandDispatchedEventTest {

    // ── Construction ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {

        @Test
        @DisplayName("all 3 fields accessible after construction")
        void allFieldsAccessible() {
            var targetRef = new Ulid(1L, 1L);
            var integrationId = new Ulid(2L, 2L);
            var event = new CommandDispatchedEvent(
                    targetRef,
                    integrationId,
                    "{\"protocol\": \"zigbee\"}");

            assertThat(event.targetEntityRef()).isEqualTo(targetRef);
            assertThat(event.integrationId()).isEqualTo(integrationId);
            assertThat(event.protocolMetadata()).isEqualTo("{\"protocol\": \"zigbee\"}");
        }

        @Test
        @DisplayName("implements DomainEvent")
        void implementsDomainEvent() {
            var event = new CommandDispatchedEvent(
                    new Ulid(1L, 1L),
                    new Ulid(2L, 2L),
                    "{}");
            assertThat(event).isInstanceOf(DomainEvent.class);
        }

        @Test
        @DisplayName("record has exactly 3 components")
        void exactlyThreeFields() {
            assertThat(CommandDispatchedEvent.class.getRecordComponents()).hasSize(3);
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
                    new CommandDispatchedEvent(
                            null,
                            new Ulid(2L, 2L),
                            "{}"))
                    .withMessageContaining("targetEntityRef");
        }

        @Test
        @DisplayName("null integrationId throws NullPointerException")
        void nullIntegrationId() {
            assertThatNullPointerException().isThrownBy(() ->
                    new CommandDispatchedEvent(
                            new Ulid(1L, 1L),
                            null,
                            "{}"))
                    .withMessageContaining("integrationId");
        }

        @Test
        @DisplayName("null protocolMetadata throws NullPointerException")
        void nullProtocolMetadata() {
            assertThatNullPointerException().isThrownBy(() ->
                    new CommandDispatchedEvent(
                            new Ulid(1L, 1L),
                            new Ulid(2L, 2L),
                            null))
                    .withMessageContaining("protocolMetadata");
        }
    }

    // ── Equals / hashCode ────────────────────────────────────────────────

    @Test
    @DisplayName("identical CommandDispatchedEvents are equal")
    void identicalEqual() {
        var a = new CommandDispatchedEvent(
                new Ulid(1L, 1L),
                new Ulid(2L, 2L),
                "{}");
        var b = new CommandDispatchedEvent(
                new Ulid(1L, 1L),
                new Ulid(2L, 2L),
                "{}");
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    @DisplayName("CommandDispatchedEvents with different fields are not equal")
    void differentNotEqual() {
        var a = new CommandDispatchedEvent(
                new Ulid(1L, 1L),
                new Ulid(2L, 2L),
                "{}");
        var b = new CommandDispatchedEvent(
                new Ulid(1L, 1L),
                new Ulid(3L, 3L),
                "{}");
        assertThat(a).isNotEqualTo(b);
    }
}
