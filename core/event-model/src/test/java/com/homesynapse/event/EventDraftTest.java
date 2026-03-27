/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.Ulid;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link EventDraft} — the pre-persistence event record.
 */
@DisplayName("EventDraft")
class EventDraftTest {

    private static final Ulid ULID_A = new Ulid(0x0191B3C4D5E6F708L, 0x0123456789ABCDEFL);
    private static final Ulid ULID_ACTOR = new Ulid(0x0191B3C4D5E6F70AL, 0x0123456789ABCDEFL);
    private static final SubjectRef SUBJECT_REF =
            SubjectRef.entity(EntityId.of(ULID_A));
    private static final Instant EVENT_TIME = Instant.parse("2026-03-15T10:00:00Z");

    private record TestPayload(String data) implements DomainEvent {}

    private static final DomainEvent PAYLOAD = new TestPayload("test");

    private static EventDraft validDraft() {
        return new EventDraft(
                "device.state_changed", 1, EVENT_TIME, SUBJECT_REF,
                EventPriority.NORMAL, EventOrigin.PHYSICAL, PAYLOAD, ULID_ACTOR);
    }

    // ── Construction ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {

        @Test
        @DisplayName("record has exactly 8 components")
        void exactlyEightFields() {
            assertThat(EventDraft.class.getRecordComponents()).hasSize(8);
        }

        @Test
        @DisplayName("all 8 fields are accessible and return correct values")
        void allFieldsAccessible() {
            var draft = validDraft();

            assertThat(draft.eventType()).isEqualTo("device.state_changed");
            assertThat(draft.schemaVersion()).isEqualTo(1);
            assertThat(draft.eventTime()).isEqualTo(EVENT_TIME);
            assertThat(draft.subjectRef()).isEqualTo(SUBJECT_REF);
            assertThat(draft.priority()).isEqualTo(EventPriority.NORMAL);
            assertThat(draft.origin()).isEqualTo(EventOrigin.PHYSICAL);
            assertThat(draft.payload()).isEqualTo(PAYLOAD);
            assertThat(draft.actorRef()).isEqualTo(ULID_ACTOR);
        }

        @Test
        @DisplayName("eventTime is nullable — null accepted")
        void eventTimeNullable() {
            var draft = new EventDraft(
                    "device.state_changed", 1, null, SUBJECT_REF,
                    EventPriority.NORMAL, EventOrigin.PHYSICAL, PAYLOAD, null);

            assertThat(draft.eventTime()).isNull();
        }

        @Test
        @DisplayName("actorRef is nullable — null accepted for system events")
        void actorRefNullable() {
            var draft = new EventDraft(
                    "system.started", 1, null, SUBJECT_REF,
                    EventPriority.CRITICAL, EventOrigin.SYSTEM, PAYLOAD, null);

            assertThat(draft.actorRef()).isNull();
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
                    new EventDraft(null, 1, EVENT_TIME, SUBJECT_REF,
                            EventPriority.NORMAL, EventOrigin.PHYSICAL, PAYLOAD, ULID_ACTOR))
                    .withMessageContaining("eventType");
        }

        @Test
        @DisplayName("null subjectRef throws NullPointerException")
        void nullSubjectRef() {
            assertThatNullPointerException().isThrownBy(() ->
                    new EventDraft("type", 1, EVENT_TIME, null,
                            EventPriority.NORMAL, EventOrigin.PHYSICAL, PAYLOAD, ULID_ACTOR))
                    .withMessageContaining("subjectRef");
        }

        @Test
        @DisplayName("null priority throws NullPointerException")
        void nullPriority() {
            assertThatNullPointerException().isThrownBy(() ->
                    new EventDraft("type", 1, EVENT_TIME, SUBJECT_REF,
                            null, EventOrigin.PHYSICAL, PAYLOAD, ULID_ACTOR))
                    .withMessageContaining("priority");
        }

        @Test
        @DisplayName("null origin throws NullPointerException")
        void nullOrigin() {
            assertThatNullPointerException().isThrownBy(() ->
                    new EventDraft("type", 1, EVENT_TIME, SUBJECT_REF,
                            EventPriority.NORMAL, null, PAYLOAD, ULID_ACTOR))
                    .withMessageContaining("origin");
        }

        @Test
        @DisplayName("null payload throws NullPointerException")
        void nullPayload() {
            assertThatNullPointerException().isThrownBy(() ->
                    new EventDraft("type", 1, EVENT_TIME, SUBJECT_REF,
                            EventPriority.NORMAL, EventOrigin.PHYSICAL, null, ULID_ACTOR))
                    .withMessageContaining("payload");
        }
    }

    // ── Range validation ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Range validation")
    class RangeValidationTests {

        @Test
        @DisplayName("blank eventType throws IllegalArgumentException")
        void blankEventType() {
            assertThatThrownBy(() ->
                    new EventDraft("   ", 1, EVENT_TIME, SUBJECT_REF,
                            EventPriority.NORMAL, EventOrigin.PHYSICAL, PAYLOAD, ULID_ACTOR))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("blank");
        }

        @Test
        @DisplayName("schemaVersion < 1 throws IllegalArgumentException")
        void schemaVersionZero() {
            assertThatThrownBy(() ->
                    new EventDraft("type", 0, EVENT_TIME, SUBJECT_REF,
                            EventPriority.NORMAL, EventOrigin.PHYSICAL, PAYLOAD, ULID_ACTOR))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("schemaVersion");
        }
    }

    // ── Equals / hashCode ────────────────────────────────────────────────

    @Test
    @DisplayName("identical drafts are equal")
    void identicalEqual() {
        var a = validDraft();
        var b = validDraft();
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }
}
