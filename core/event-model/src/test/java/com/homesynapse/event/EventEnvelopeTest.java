/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.Ulid;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link EventEnvelope} — the most critical record type in the system.
 *
 * <p>Verifies all 14 fields, null validation, range validation, defensive copy
 * of categories, and equals/hashCode behavior.</p>
 */
@DisplayName("EventEnvelope")
class EventEnvelopeTest {

    // ── Deterministic test fixtures ──────────────────────────────────────

    private static final Ulid ULID_A = new Ulid(0x0191B3C4D5E6F708L, 0x0123456789ABCDEFL);
    private static final Ulid ULID_B = new Ulid(0x0191B3C4D5E6F709L, 0x0123456789ABCDEFL);
    private static final Ulid ULID_C = new Ulid(0x0191B3C4D5E6F70AL, 0x0123456789ABCDEFL);

    private static final EventId EVENT_ID = EventId.of(ULID_A);
    private static final SubjectRef SUBJECT_REF =
            SubjectRef.entity(EntityId.of(ULID_B));
    private static final CausalContext CAUSAL_CONTEXT = CausalContext.root(ULID_A);
    private static final Instant NOW = Instant.parse("2026-03-15T10:00:00Z");

    /** Simple DomainEvent implementation for testing. */
    private record TestPayload(String data) implements DomainEvent {}

    private static final DomainEvent PAYLOAD = new TestPayload("test");

    /**
     * Constructs a valid envelope with all 14 fields for reuse across tests.
     */
    private static EventEnvelope validEnvelope() {
        return new EventEnvelope(
                EVENT_ID,                                  // 1. eventId
                "device.state_changed",                    // 2. eventType
                1,                                         // 3. schemaVersion
                NOW,                                       // 4. ingestTime
                NOW.minusSeconds(1),                       // 5. eventTime (nullable)
                SUBJECT_REF,                               // 6. subjectRef
                1L,                                        // 7. subjectSequence (long)
                100L,                                      // 8. globalPosition (long)
                EventPriority.NORMAL,                      // 9. priority
                EventOrigin.PHYSICAL,                      // 10. origin
                List.of(EventCategory.DEVICE_STATE),       // 11. categories
                CAUSAL_CONTEXT,                            // 12. causalContext
                ULID_C,                                    // 13. actorRef (nullable)
                PAYLOAD                                    // 14. payload
        );
    }

    // ── Construction and field access ────────────────────────────────────

    @Nested
    @DisplayName("Construction and field access")
    class ConstructionTests {

        @Test
        @DisplayName("all 14 fields are accessible and return correct values")
        void allFieldsAccessible() {
            Instant eventTime = NOW.minusSeconds(1);
            var envelope = new EventEnvelope(
                    EVENT_ID, "device.state_changed", 1, NOW, eventTime,
                    SUBJECT_REF, 1L, 100L, EventPriority.NORMAL,
                    EventOrigin.PHYSICAL, List.of(EventCategory.DEVICE_STATE),
                    CAUSAL_CONTEXT, ULID_C, PAYLOAD);

            assertThat(envelope.eventId()).isEqualTo(EVENT_ID);
            assertThat(envelope.eventType()).isEqualTo("device.state_changed");
            assertThat(envelope.schemaVersion()).isEqualTo(1);
            assertThat(envelope.ingestTime()).isEqualTo(NOW);
            assertThat(envelope.eventTime()).isEqualTo(eventTime);
            assertThat(envelope.subjectRef()).isEqualTo(SUBJECT_REF);
            assertThat(envelope.subjectSequence()).isEqualTo(1L);
            assertThat(envelope.globalPosition()).isEqualTo(100L);
            assertThat(envelope.priority()).isEqualTo(EventPriority.NORMAL);
            assertThat(envelope.origin()).isEqualTo(EventOrigin.PHYSICAL);
            assertThat(envelope.categories()).containsExactly(EventCategory.DEVICE_STATE);
            assertThat(envelope.causalContext()).isEqualTo(CAUSAL_CONTEXT);
            assertThat(envelope.actorRef()).isEqualTo(ULID_C);
            assertThat(envelope.payload()).isEqualTo(PAYLOAD);
        }

        @Test
        @DisplayName("exactly 14 constructor parameters (verified via record component count)")
        void exactlyFourteenFields() {
            assertThat(EventEnvelope.class.getRecordComponents()).hasSize(14);
        }

        @Test
        @DisplayName("actorRef is nullable — null accepted for system/autonomous events")
        void actorRefNullAccepted() {
            var envelope = new EventEnvelope(
                    EVENT_ID, "system.started", 1, NOW, null,
                    SUBJECT_REF, 1L, 0L, EventPriority.CRITICAL,
                    EventOrigin.SYSTEM, List.of(EventCategory.SYSTEM),
                    CAUSAL_CONTEXT, null, PAYLOAD);

            assertThat(envelope.actorRef()).isNull();
        }

        @Test
        @DisplayName("actorRef is nullable — non-null accepted for user-attributed events")
        void actorRefNonNullAccepted() {
            var envelope = validEnvelope();
            assertThat(envelope.actorRef()).isEqualTo(ULID_C);
        }

        @Test
        @DisplayName("eventTime is nullable — null accepted")
        void eventTimeNullAccepted() {
            var envelope = new EventEnvelope(
                    EVENT_ID, "device.state_changed", 1, NOW, null,
                    SUBJECT_REF, 1L, 100L, EventPriority.NORMAL,
                    EventOrigin.PHYSICAL, List.of(EventCategory.DEVICE_STATE),
                    CAUSAL_CONTEXT, ULID_C, PAYLOAD);

            assertThat(envelope.eventTime()).isNull();
        }

        @Test
        @DisplayName("eventTime is nullable — non-null accepted")
        void eventTimeNonNullAccepted() {
            Instant eventTime = NOW.minusSeconds(5);
            var envelope = new EventEnvelope(
                    EVENT_ID, "device.state_changed", 1, NOW, eventTime,
                    SUBJECT_REF, 1L, 100L, EventPriority.NORMAL,
                    EventOrigin.PHYSICAL, List.of(EventCategory.DEVICE_STATE),
                    CAUSAL_CONTEXT, ULID_C, PAYLOAD);

            assertThat(envelope.eventTime()).isEqualTo(eventTime);
        }

        @Test
        @DisplayName("causalContext is embedded as a record field and accessible")
        void causalContextAccessible() {
            var envelope = validEnvelope();

            assertThat(envelope.causalContext()).isNotNull();
            assertThat(envelope.causalContext().correlationId()).isEqualTo(ULID_A);
            assertThat(envelope.causalContext().isRoot()).isTrue();
        }
    }

    // ── Field type verification ──────────────────────────────────────────

    @Nested
    @DisplayName("Field type verification")
    class FieldTypeTests {

        @Test
        @DisplayName("subjectSequence is long — value exceeding Integer.MAX_VALUE accepted")
        void subjectSequenceIsLong() {
            long largeSequence = (long) Integer.MAX_VALUE + 100L;
            var envelope = new EventEnvelope(
                    EVENT_ID, "device.state_changed", 1, NOW, null,
                    SUBJECT_REF, largeSequence, 100L, EventPriority.NORMAL,
                    EventOrigin.PHYSICAL, List.of(EventCategory.DEVICE_STATE),
                    CAUSAL_CONTEXT, null, PAYLOAD);

            assertThat(envelope.subjectSequence()).isEqualTo(largeSequence);
            assertThat(envelope.subjectSequence()).isGreaterThan(Integer.MAX_VALUE);
        }

        @Test
        @DisplayName("globalPosition is long — large value accepted")
        void globalPositionIsLong() {
            long largePosition = (long) Integer.MAX_VALUE + 500L;
            var envelope = new EventEnvelope(
                    EVENT_ID, "device.state_changed", 1, NOW, null,
                    SUBJECT_REF, 1L, largePosition, EventPriority.NORMAL,
                    EventOrigin.PHYSICAL, List.of(EventCategory.DEVICE_STATE),
                    CAUSAL_CONTEXT, null, PAYLOAD);

            assertThat(envelope.globalPosition()).isEqualTo(largePosition);
            assertThat(envelope.globalPosition()).isGreaterThan(Integer.MAX_VALUE);
        }

        @Test
        @DisplayName("schemaVersion is int")
        void schemaVersionIsInt() {
            var envelope = validEnvelope();
            assertThat(envelope.schemaVersion()).isEqualTo(1);
        }

        @Test
        @DisplayName("categories is List<EventCategory>")
        void categoriesIsList() {
            var envelope = validEnvelope();
            assertThat(envelope.categories())
                    .isInstanceOf(List.class)
                    .isNotEmpty();
        }
    }

    // ── Null validation ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Null validation")
    class NullValidationTests {

        @Test
        @DisplayName("null eventId throws NullPointerException")
        void nullEventId() {
            assertThatNullPointerException().isThrownBy(() ->
                    new EventEnvelope(
                            null, "type", 1, NOW, null, SUBJECT_REF, 1L, 0L,
                            EventPriority.NORMAL, EventOrigin.SYSTEM,
                            List.of(EventCategory.SYSTEM), CAUSAL_CONTEXT, null, PAYLOAD))
                    .withMessageContaining("eventId");
        }

        @Test
        @DisplayName("null eventType throws NullPointerException")
        void nullEventType() {
            assertThatNullPointerException().isThrownBy(() ->
                    new EventEnvelope(
                            EVENT_ID, null, 1, NOW, null, SUBJECT_REF, 1L, 0L,
                            EventPriority.NORMAL, EventOrigin.SYSTEM,
                            List.of(EventCategory.SYSTEM), CAUSAL_CONTEXT, null, PAYLOAD))
                    .withMessageContaining("eventType");
        }

        @Test
        @DisplayName("null ingestTime throws NullPointerException")
        void nullIngestTime() {
            assertThatNullPointerException().isThrownBy(() ->
                    new EventEnvelope(
                            EVENT_ID, "type", 1, null, null, SUBJECT_REF, 1L, 0L,
                            EventPriority.NORMAL, EventOrigin.SYSTEM,
                            List.of(EventCategory.SYSTEM), CAUSAL_CONTEXT, null, PAYLOAD))
                    .withMessageContaining("ingestTime");
        }

        @Test
        @DisplayName("null subjectRef throws NullPointerException")
        void nullSubjectRef() {
            assertThatNullPointerException().isThrownBy(() ->
                    new EventEnvelope(
                            EVENT_ID, "type", 1, NOW, null, null, 1L, 0L,
                            EventPriority.NORMAL, EventOrigin.SYSTEM,
                            List.of(EventCategory.SYSTEM), CAUSAL_CONTEXT, null, PAYLOAD))
                    .withMessageContaining("subjectRef");
        }

        @Test
        @DisplayName("null priority throws NullPointerException")
        void nullPriority() {
            assertThatNullPointerException().isThrownBy(() ->
                    new EventEnvelope(
                            EVENT_ID, "type", 1, NOW, null, SUBJECT_REF, 1L, 0L,
                            null, EventOrigin.SYSTEM,
                            List.of(EventCategory.SYSTEM), CAUSAL_CONTEXT, null, PAYLOAD))
                    .withMessageContaining("priority");
        }

        @Test
        @DisplayName("null origin throws NullPointerException")
        void nullOrigin() {
            assertThatNullPointerException().isThrownBy(() ->
                    new EventEnvelope(
                            EVENT_ID, "type", 1, NOW, null, SUBJECT_REF, 1L, 0L,
                            EventPriority.NORMAL, null,
                            List.of(EventCategory.SYSTEM), CAUSAL_CONTEXT, null, PAYLOAD))
                    .withMessageContaining("origin");
        }

        @Test
        @DisplayName("null categories throws NullPointerException")
        void nullCategories() {
            assertThatNullPointerException().isThrownBy(() ->
                    new EventEnvelope(
                            EVENT_ID, "type", 1, NOW, null, SUBJECT_REF, 1L, 0L,
                            EventPriority.NORMAL, EventOrigin.SYSTEM,
                            null, CAUSAL_CONTEXT, null, PAYLOAD))
                    .withMessageContaining("categories");
        }

        @Test
        @DisplayName("null causalContext throws NullPointerException")
        void nullCausalContext() {
            assertThatNullPointerException().isThrownBy(() ->
                    new EventEnvelope(
                            EVENT_ID, "type", 1, NOW, null, SUBJECT_REF, 1L, 0L,
                            EventPriority.NORMAL, EventOrigin.SYSTEM,
                            List.of(EventCategory.SYSTEM), null, null, PAYLOAD))
                    .withMessageContaining("causalContext");
        }

        @Test
        @DisplayName("null payload throws NullPointerException")
        void nullPayload() {
            assertThatNullPointerException().isThrownBy(() ->
                    new EventEnvelope(
                            EVENT_ID, "type", 1, NOW, null, SUBJECT_REF, 1L, 0L,
                            EventPriority.NORMAL, EventOrigin.SYSTEM,
                            List.of(EventCategory.SYSTEM), CAUSAL_CONTEXT, null, null))
                    .withMessageContaining("payload");
        }
    }

    // ── Range and constraint validation ──────────────────────────────────

    @Nested
    @DisplayName("Range and constraint validation")
    class RangeValidationTests {

        @Test
        @DisplayName("blank eventType throws IllegalArgumentException")
        void blankEventType() {
            assertThatThrownBy(() ->
                    new EventEnvelope(
                            EVENT_ID, "   ", 1, NOW, null, SUBJECT_REF, 1L, 0L,
                            EventPriority.NORMAL, EventOrigin.SYSTEM,
                            List.of(EventCategory.SYSTEM), CAUSAL_CONTEXT, null, PAYLOAD))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("blank");
        }

        @Test
        @DisplayName("schemaVersion < 1 throws IllegalArgumentException")
        void schemaVersionZero() {
            assertThatThrownBy(() ->
                    new EventEnvelope(
                            EVENT_ID, "type", 0, NOW, null, SUBJECT_REF, 1L, 0L,
                            EventPriority.NORMAL, EventOrigin.SYSTEM,
                            List.of(EventCategory.SYSTEM), CAUSAL_CONTEXT, null, PAYLOAD))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("schemaVersion");
        }

        @Test
        @DisplayName("subjectSequence < 1 throws IllegalArgumentException")
        void subjectSequenceZero() {
            assertThatThrownBy(() ->
                    new EventEnvelope(
                            EVENT_ID, "type", 1, NOW, null, SUBJECT_REF, 0L, 0L,
                            EventPriority.NORMAL, EventOrigin.SYSTEM,
                            List.of(EventCategory.SYSTEM), CAUSAL_CONTEXT, null, PAYLOAD))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("subjectSequence");
        }

        @Test
        @DisplayName("negative globalPosition throws IllegalArgumentException")
        void negativeGlobalPosition() {
            assertThatThrownBy(() ->
                    new EventEnvelope(
                            EVENT_ID, "type", 1, NOW, null, SUBJECT_REF, 1L, -1L,
                            EventPriority.NORMAL, EventOrigin.SYSTEM,
                            List.of(EventCategory.SYSTEM), CAUSAL_CONTEXT, null, PAYLOAD))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("globalPosition");
        }

        @Test
        @DisplayName("globalPosition 0 is accepted (pre-persistence state)")
        void globalPositionZeroAccepted() {
            var envelope = new EventEnvelope(
                    EVENT_ID, "type", 1, NOW, null, SUBJECT_REF, 1L, 0L,
                    EventPriority.NORMAL, EventOrigin.SYSTEM,
                    List.of(EventCategory.SYSTEM), CAUSAL_CONTEXT, null, PAYLOAD);

            assertThat(envelope.globalPosition()).isZero();
        }

        @Test
        @DisplayName("empty categories list throws IllegalArgumentException")
        void emptyCategories() {
            assertThatThrownBy(() ->
                    new EventEnvelope(
                            EVENT_ID, "type", 1, NOW, null, SUBJECT_REF, 1L, 0L,
                            EventPriority.NORMAL, EventOrigin.SYSTEM,
                            List.of(), CAUSAL_CONTEXT, null, PAYLOAD))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("categories");
        }

        @Test
        @DisplayName("categories list is defensively copied — external mutation has no effect")
        void categoriesDefensivelyCopied() {
            var mutable = new ArrayList<>(List.of(EventCategory.DEVICE_STATE));
            var envelope = new EventEnvelope(
                    EVENT_ID, "type", 1, NOW, null, SUBJECT_REF, 1L, 0L,
                    EventPriority.NORMAL, EventOrigin.SYSTEM,
                    mutable, CAUSAL_CONTEXT, null, PAYLOAD);

            mutable.add(EventCategory.ENERGY);

            assertThat(envelope.categories()).hasSize(1);
            assertThat(envelope.categories()).containsExactly(EventCategory.DEVICE_STATE);
        }

        @Test
        @DisplayName("categories list is immutable — modification throws UnsupportedOperationException")
        void categoriesImmutable() {
            var envelope = validEnvelope();

            assertThatThrownBy(() -> envelope.categories().add(EventCategory.ENERGY))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    // ── Equals / hashCode ────────────────────────────────────────────────

    @Nested
    @DisplayName("equals / hashCode")
    class EqualsHashCodeTests {

        @Test
        @DisplayName("two envelopes with identical fields are equal")
        void identicalFieldsAreEqual() {
            var a = validEnvelope();
            var b = validEnvelope();

            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        @DisplayName("envelopes differing in eventId are not equal")
        void differentEventId() {
            var a = validEnvelope();
            var b = new EventEnvelope(
                    EventId.of(ULID_B), "device.state_changed", 1, NOW,
                    NOW.minusSeconds(1), SUBJECT_REF, 1L, 100L,
                    EventPriority.NORMAL, EventOrigin.PHYSICAL,
                    List.of(EventCategory.DEVICE_STATE), CAUSAL_CONTEXT, ULID_C, PAYLOAD);

            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("envelopes differing in eventType are not equal")
        void differentEventType() {
            var a = validEnvelope();
            var b = new EventEnvelope(
                    EVENT_ID, "device.command_issued", 1, NOW,
                    NOW.minusSeconds(1), SUBJECT_REF, 1L, 100L,
                    EventPriority.NORMAL, EventOrigin.PHYSICAL,
                    List.of(EventCategory.DEVICE_STATE), CAUSAL_CONTEXT, ULID_C, PAYLOAD);

            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("envelopes differing in subjectSequence are not equal")
        void differentSubjectSequence() {
            var a = validEnvelope();
            var b = new EventEnvelope(
                    EVENT_ID, "device.state_changed", 1, NOW,
                    NOW.minusSeconds(1), SUBJECT_REF, 2L, 100L,
                    EventPriority.NORMAL, EventOrigin.PHYSICAL,
                    List.of(EventCategory.DEVICE_STATE), CAUSAL_CONTEXT, ULID_C, PAYLOAD);

            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("envelopes differing in actorRef are not equal")
        void differentActorRef() {
            var a = validEnvelope();
            var b = new EventEnvelope(
                    EVENT_ID, "device.state_changed", 1, NOW,
                    NOW.minusSeconds(1), SUBJECT_REF, 1L, 100L,
                    EventPriority.NORMAL, EventOrigin.PHYSICAL,
                    List.of(EventCategory.DEVICE_STATE), CAUSAL_CONTEXT, null, PAYLOAD);

            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("envelopes differing in globalPosition are not equal")
        void differentGlobalPosition() {
            var a = validEnvelope();
            var b = new EventEnvelope(
                    EVENT_ID, "device.state_changed", 1, NOW,
                    NOW.minusSeconds(1), SUBJECT_REF, 1L, 200L,
                    EventPriority.NORMAL, EventOrigin.PHYSICAL,
                    List.of(EventCategory.DEVICE_STATE), CAUSAL_CONTEXT, ULID_C, PAYLOAD);

            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("envelopes differing in priority are not equal")
        void differentPriority() {
            var a = validEnvelope();
            var b = new EventEnvelope(
                    EVENT_ID, "device.state_changed", 1, NOW,
                    NOW.minusSeconds(1), SUBJECT_REF, 1L, 100L,
                    EventPriority.CRITICAL, EventOrigin.PHYSICAL,
                    List.of(EventCategory.DEVICE_STATE), CAUSAL_CONTEXT, ULID_C, PAYLOAD);

            assertThat(a).isNotEqualTo(b);
        }
    }
}
