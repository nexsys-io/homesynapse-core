/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event.test;

import java.time.Instant;
import java.util.List;

import com.homesynapse.event.CausalContext;
import com.homesynapse.event.DomainEvent;
import com.homesynapse.event.EventCategory;
import com.homesynapse.event.EventDraft;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventId;
import com.homesynapse.event.EventOrigin;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.platform.identity.AutomationId;
import com.homesynapse.platform.identity.DeviceId;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.IntegrationId;
import com.homesynapse.platform.identity.PersonId;
import com.homesynapse.platform.identity.SystemId;
import com.homesynapse.platform.identity.Ulid;
import com.homesynapse.platform.identity.UlidFactory;

/**
 * Static factory methods and builders for creating {@link EventDraft} and
 * {@link EventEnvelope} instances with sensible defaults in tests.
 *
 * <p>Without this class, every test that needs an {@code EventDraft} must construct
 * the full 8-field record manually, and every test that needs an {@code EventEnvelope}
 * must construct all 14 fields. This class provides one-liner defaults for the common
 * case and builders for full customization.</p>
 *
 * <p>This class also provides {@link SubjectRef} factory methods that generate fresh
 * ULID-based subjects of each type, and a minimal {@link DomainEvent} implementation
 * ({@link Payload}) for use in any test that needs a payload.</p>
 *
 * <p>This class is a test fixture — it lives in the {@code testFixtures} source set
 * and is consumed by downstream modules via
 * {@code testFixtures(project(":core:event-model"))}.</p>
 *
 * @see EventDraft
 * @see EventEnvelope
 * @see TestCausalContext
 */
public final class TestEventFactory {

    /** Default event type — clearly synthetic, will not collide with real event types. */
    private static final String DEFAULT_EVENT_TYPE = "test.event";

    /** Default schema version for test events. */
    private static final int DEFAULT_SCHEMA_VERSION = 1;

    private TestEventFactory() {
        // Utility class — no instantiation.
    }

    // ──────────────────────────────────────────────────────────────────
    // Test payload
    // ──────────────────────────────────────────────────────────────────

    /**
     * A minimal {@link DomainEvent} implementation for cross-module test use.
     *
     * <p>This serves the same role as {@code EventStoreContractTest.TestPayload}
     * but is publicly accessible to any module that depends on the event-model
     * test fixtures.</p>
     *
     * @param value an arbitrary string value for test identification
     */
    public record Payload(String value) implements DomainEvent {}

    // ──────────────────────────────────────────────────────────────────
    // EventDraft creation
    // ──────────────────────────────────────────────────────────────────

    /**
     * Creates a minimal {@link EventDraft} with all default values.
     *
     * <p>Defaults: eventType {@code "test.event"}, schemaVersion 1, eventTime null,
     * a fresh unique entity subject, priority NORMAL, origin SYSTEM,
     * payload {@code Payload("test")}, actorRef null.</p>
     *
     * @return a valid EventDraft with sensible defaults
     */
    public static EventDraft draft() {
        return new EventDraft(
                DEFAULT_EVENT_TYPE,
                DEFAULT_SCHEMA_VERSION,
                null,
                subject(),
                EventPriority.NORMAL,
                EventOrigin.SYSTEM,
                new Payload("test"),
                null
        );
    }

    /**
     * Creates an {@link EventDraft} for a specific subject with default event type.
     *
     * @param subject the subject this event is about; never {@code null}
     * @return a valid EventDraft for the given subject
     */
    public static EventDraft draftFor(SubjectRef subject) {
        return new EventDraft(
                DEFAULT_EVENT_TYPE,
                DEFAULT_SCHEMA_VERSION,
                null,
                subject,
                EventPriority.NORMAL,
                EventOrigin.SYSTEM,
                new Payload("test"),
                null
        );
    }

    /**
     * Creates an {@link EventDraft} for a specific subject and event type.
     *
     * <p>Matches the parameter order used by
     * {@link EventStoreContractTest#draftFor(SubjectRef, String)}.</p>
     *
     * @param subject   the subject this event is about; never {@code null}
     * @param eventType the event type string; never {@code null} or blank
     * @return a valid EventDraft for the given subject and type
     */
    public static EventDraft draftFor(SubjectRef subject, String eventType) {
        return new EventDraft(
                eventType,
                DEFAULT_SCHEMA_VERSION,
                null,
                subject,
                EventPriority.NORMAL,
                EventOrigin.SYSTEM,
                new Payload("test"),
                null
        );
    }

    /**
     * Returns a builder for full customization of an {@link EventDraft}.
     *
     * <p>All fields are pre-initialized to sensible defaults. Override only
     * the fields you need.</p>
     *
     * @return a new DraftBuilder with default values
     */
    public static DraftBuilder draftBuilder() {
        return new DraftBuilder();
    }

    // ──────────────────────────────────────────────────────────────────
    // SubjectRef creation helpers
    // ──────────────────────────────────────────────────────────────────

    /**
     * Creates a fresh unique entity subject. This is the most common
     * subject type needed in tests.
     *
     * @return a new SubjectRef with type ENTITY and a unique EntityId
     */
    public static SubjectRef subject() {
        return SubjectRef.entity(new EntityId(UlidFactory.generate()));
    }

    /**
     * Creates a fresh unique device subject.
     *
     * @return a new SubjectRef with type DEVICE and a unique DeviceId
     */
    public static SubjectRef deviceSubject() {
        return SubjectRef.device(new DeviceId(UlidFactory.generate()));
    }

    /**
     * Creates a fresh unique integration subject.
     *
     * @return a new SubjectRef with type INTEGRATION and a unique IntegrationId
     */
    public static SubjectRef integrationSubject() {
        return SubjectRef.integration(new IntegrationId(UlidFactory.generate()));
    }

    /**
     * Creates a fresh unique automation subject.
     *
     * @return a new SubjectRef with type AUTOMATION and a unique AutomationId
     */
    public static SubjectRef automationSubject() {
        return SubjectRef.automation(new AutomationId(UlidFactory.generate()));
    }

    /**
     * Creates a fresh unique system subject.
     *
     * @return a new SubjectRef with type SYSTEM and a unique SystemId
     */
    public static SubjectRef systemSubject() {
        return SubjectRef.system(new SystemId(UlidFactory.generate()));
    }

    /**
     * Creates a fresh unique person subject.
     *
     * @return a new SubjectRef with type PERSON and a unique PersonId
     */
    public static SubjectRef personSubject() {
        return SubjectRef.person(new PersonId(UlidFactory.generate()));
    }

    // ──────────────────────────────────────────────────────────────────
    // EventEnvelope creation
    // ──────────────────────────────────────────────────────────────────

    /**
     * Creates an {@link EventEnvelope} with all publisher-assigned fields
     * filled in using generated values.
     *
     * <p>This is for tests that need a pre-built envelope without going through
     * {@link InMemoryEventStore}. The envelope is valid per the compact constructor
     * but does not correspond to an actual event in any store.</p>
     *
     * @return a valid EventEnvelope with generated defaults
     */
    public static EventEnvelope envelope() {
        EventId eventId = EventId.of(UlidFactory.generate());
        return new EventEnvelope(
                eventId,
                DEFAULT_EVENT_TYPE,
                DEFAULT_SCHEMA_VERSION,
                Instant.now(),
                null,
                subject(),
                1L,
                1L,
                EventPriority.NORMAL,
                EventOrigin.SYSTEM,
                List.of(EventCategory.SYSTEM),
                CausalContext.root(eventId.value()),
                null,
                new Payload("test")
        );
    }

    /**
     * Creates an {@link EventEnvelope} for a specific subject.
     *
     * @param subject the subject this event is about; never {@code null}
     * @return a valid EventEnvelope for the given subject
     */
    public static EventEnvelope envelopeFor(SubjectRef subject) {
        EventId eventId = EventId.of(UlidFactory.generate());
        return new EventEnvelope(
                eventId,
                DEFAULT_EVENT_TYPE,
                DEFAULT_SCHEMA_VERSION,
                Instant.now(),
                null,
                subject,
                1L,
                1L,
                EventPriority.NORMAL,
                EventOrigin.SYSTEM,
                List.of(EventCategory.SYSTEM),
                CausalContext.root(eventId.value()),
                null,
                new Payload("test")
        );
    }

    /**
     * Returns a builder for full customization of an {@link EventEnvelope}.
     *
     * <p>All 14 fields are pre-initialized to sensible defaults including
     * publisher-assigned fields (eventId, ingestTime, globalPosition,
     * subjectSequence, categories, causalContext). Override only
     * the fields you need.</p>
     *
     * @return a new EnvelopeBuilder with default values
     */
    public static EnvelopeBuilder envelopeBuilder() {
        return new EnvelopeBuilder();
    }

    // ──────────────────────────────────────────────────────────────────
    // DraftBuilder
    // ──────────────────────────────────────────────────────────────────

    /**
     * Mutable builder for {@link EventDraft} instances with fluent API.
     *
     * <p>All fields are pre-initialized to the same sensible defaults used by
     * {@link TestEventFactory#draft()}. Call setters only for the fields you
     * want to override, then call {@link #build()}.</p>
     */
    public static final class DraftBuilder {

        private String eventType = DEFAULT_EVENT_TYPE;
        private int schemaVersion = DEFAULT_SCHEMA_VERSION;
        private Instant eventTime;
        private SubjectRef subject;
        private EventPriority priority = EventPriority.NORMAL;
        private EventOrigin origin = EventOrigin.SYSTEM;
        private DomainEvent payload = new Payload("test");
        private Ulid actorRef;

        DraftBuilder() {
            // Package-private — created via TestEventFactory.draftBuilder()
        }

        /** Sets the event type string. */
        public DraftBuilder eventType(String eventType) {
            this.eventType = eventType;
            return this;
        }

        /** Sets the schema version (must be {@code >= 1}). */
        public DraftBuilder schemaVersion(int schemaVersion) {
            this.schemaVersion = schemaVersion;
            return this;
        }

        /** Sets the event time ({@code null} for no real-world timestamp). */
        public DraftBuilder eventTime(Instant eventTime) {
            this.eventTime = eventTime;
            return this;
        }

        /** Sets the subject reference for this event. */
        public DraftBuilder subject(SubjectRef subject) {
            this.subject = subject;
            return this;
        }

        /** Sets the event priority. */
        public DraftBuilder priority(EventPriority priority) {
            this.priority = priority;
            return this;
        }

        /** Sets the event origin. */
        public DraftBuilder origin(EventOrigin origin) {
            this.origin = origin;
            return this;
        }

        /** Sets the event payload. */
        public DraftBuilder payload(DomainEvent payload) {
            this.payload = payload;
            return this;
        }

        /** Sets the actor reference ({@code null} for system/autonomous events). */
        public DraftBuilder actorRef(Ulid actorRef) {
            this.actorRef = actorRef;
            return this;
        }

        /**
         * Builds the {@link EventDraft} record.
         *
         * <p>If no subject was set, a fresh unique entity subject is generated.</p>
         *
         * @return a valid EventDraft
         * @throws NullPointerException     if any non-nullable field is {@code null}
         * @throws IllegalArgumentException if eventType is blank or schemaVersion {@code < 1}
         */
        public EventDraft build() {
            SubjectRef effectiveSubject = subject != null
                    ? subject : TestEventFactory.subject();
            return new EventDraft(
                    eventType,
                    schemaVersion,
                    eventTime,
                    effectiveSubject,
                    priority,
                    origin,
                    payload,
                    actorRef
            );
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // EnvelopeBuilder
    // ──────────────────────────────────────────────────────────────────

    /**
     * Mutable builder for {@link EventEnvelope} instances with fluent API.
     *
     * <p>All 14 fields are pre-initialized to sensible defaults. Publisher-assigned
     * fields ({@code eventId}, {@code ingestTime}, {@code globalPosition},
     * {@code subjectSequence}, {@code categories}, {@code causalContext}) receive
     * generated defaults. Override only the fields you need, then call
     * {@link #build()}.</p>
     *
     * <p><strong>Note:</strong> The default {@code causalContext} is a root context
     * tied to the default {@code eventId}. If you override {@code eventId}, you may
     * also want to override {@code causalContext} to keep them consistent.</p>
     */
    public static final class EnvelopeBuilder {

        private EventId eventId;
        private String eventType = DEFAULT_EVENT_TYPE;
        private int schemaVersion = DEFAULT_SCHEMA_VERSION;
        private Instant ingestTime;
        private Instant eventTime;
        private SubjectRef subjectRef;
        private long subjectSequence = 1L;
        private long globalPosition = 1L;
        private EventPriority priority = EventPriority.NORMAL;
        private EventOrigin origin = EventOrigin.SYSTEM;
        private List<EventCategory> categories = List.of(EventCategory.SYSTEM);
        private CausalContext causalContext;
        private Ulid actorRef;
        private DomainEvent payload = new Payload("test");

        EnvelopeBuilder() {
            // Package-private — created via TestEventFactory.envelopeBuilder()
        }

        /** Sets the event identifier. */
        public EnvelopeBuilder eventId(EventId eventId) {
            this.eventId = eventId;
            return this;
        }

        /** Sets the event type string. */
        public EnvelopeBuilder eventType(String eventType) {
            this.eventType = eventType;
            return this;
        }

        /** Sets the schema version (must be {@code >= 1}). */
        public EnvelopeBuilder schemaVersion(int schemaVersion) {
            this.schemaVersion = schemaVersion;
            return this;
        }

        /** Sets the ingest time (system clock at append time). */
        public EnvelopeBuilder ingestTime(Instant ingestTime) {
            this.ingestTime = ingestTime;
            return this;
        }

        /** Sets the event time ({@code null} for no real-world timestamp). */
        public EnvelopeBuilder eventTime(Instant eventTime) {
            this.eventTime = eventTime;
            return this;
        }

        /** Sets the subject reference. */
        public EnvelopeBuilder subjectRef(SubjectRef subjectRef) {
            this.subjectRef = subjectRef;
            return this;
        }

        /** Sets the per-subject sequence number (must be {@code >= 1}). */
        public EnvelopeBuilder subjectSequence(long subjectSequence) {
            this.subjectSequence = subjectSequence;
            return this;
        }

        /** Sets the global position (must be {@code >= 0}). */
        public EnvelopeBuilder globalPosition(long globalPosition) {
            this.globalPosition = globalPosition;
            return this;
        }

        /** Sets the event priority. */
        public EnvelopeBuilder priority(EventPriority priority) {
            this.priority = priority;
            return this;
        }

        /** Sets the event origin. */
        public EnvelopeBuilder origin(EventOrigin origin) {
            this.origin = origin;
            return this;
        }

        /** Sets the event categories (must be non-empty). */
        public EnvelopeBuilder categories(List<EventCategory> categories) {
            this.categories = categories;
            return this;
        }

        /** Sets the causal context. */
        public EnvelopeBuilder causalContext(CausalContext causalContext) {
            this.causalContext = causalContext;
            return this;
        }

        /** Sets the actor reference ({@code null} for system/autonomous events). */
        public EnvelopeBuilder actorRef(Ulid actorRef) {
            this.actorRef = actorRef;
            return this;
        }

        /** Sets the event payload. */
        public EnvelopeBuilder payload(DomainEvent payload) {
            this.payload = payload;
            return this;
        }

        /**
         * Builds the {@link EventEnvelope} record.
         *
         * <p>If {@code eventId}, {@code ingestTime}, {@code subjectRef}, or
         * {@code causalContext} were not explicitly set, they receive generated
         * defaults.</p>
         *
         * @return a valid EventEnvelope
         * @throws NullPointerException     if any non-nullable field is {@code null}
         * @throws IllegalArgumentException if field constraints are violated
         */
        public EventEnvelope build() {
            EventId effectiveEventId = eventId != null
                    ? eventId : EventId.of(UlidFactory.generate());
            Instant effectiveIngestTime = ingestTime != null
                    ? ingestTime : Instant.now();
            SubjectRef effectiveSubjectRef = subjectRef != null
                    ? subjectRef : TestEventFactory.subject();
            CausalContext effectiveCausalContext = causalContext != null
                    ? causalContext : CausalContext.root(effectiveEventId.value());

            return new EventEnvelope(
                    effectiveEventId,
                    eventType,
                    schemaVersion,
                    effectiveIngestTime,
                    eventTime,
                    effectiveSubjectRef,
                    subjectSequence,
                    globalPosition,
                    priority,
                    origin,
                    categories,
                    effectiveCausalContext,
                    actorRef,
                    payload
            );
        }
    }
}
