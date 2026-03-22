/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.test.assertions;

import com.homesynapse.event.CausalContext;
import com.homesynapse.event.DomainEvent;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventOrigin;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.platform.identity.Ulid;

import org.assertj.core.api.AbstractAssert;

import java.time.Instant;
import java.util.Objects;

/**
 * AssertJ assertion class for {@link EventEnvelope}.
 *
 * <p>Usage:
 * <pre>{@code
 * assertThat(envelope)
 *     .hasEventType("state_changed")
 *     .hasPriority(EventPriority.NORMAL)
 *     .hasCorrelationId(rootEvent.eventId().value())
 *     .hasNonNullActorRef();
 * }</pre>
 *
 * @see HomeSynapseAssertions#assertThat(EventEnvelope)
 */
public final class EventEnvelopeAssert
        extends AbstractAssert<EventEnvelopeAssert, EventEnvelope> {

    EventEnvelopeAssert(EventEnvelope actual) {
        super(actual, EventEnvelopeAssert.class);
    }

    /**
     * Verifies the event type matches the expected value.
     *
     * @param expectedEventType the expected event type string
     * @return this assertion for chaining
     */
    public EventEnvelopeAssert hasEventType(String expectedEventType) {
        isNotNull();
        if (!Objects.equals(actual.eventType(), expectedEventType)) {
            failWithMessage("Expected event type <%s> but was <%s>",
                    expectedEventType, actual.eventType());
        }
        return this;
    }

    /**
     * Verifies the priority matches the expected value.
     *
     * @param expectedPriority the expected priority tier
     * @return this assertion for chaining
     */
    public EventEnvelopeAssert hasPriority(EventPriority expectedPriority) {
        isNotNull();
        if (actual.priority() != expectedPriority) {
            failWithMessage("Expected priority <%s> but was <%s>",
                    expectedPriority, actual.priority());
        }
        return this;
    }

    /**
     * Verifies the origin matches the expected value.
     *
     * @param expectedOrigin the expected event origin
     * @return this assertion for chaining
     */
    public EventEnvelopeAssert hasOrigin(EventOrigin expectedOrigin) {
        isNotNull();
        if (actual.origin() != expectedOrigin) {
            failWithMessage("Expected origin <%s> but was <%s>",
                    expectedOrigin, actual.origin());
        }
        return this;
    }

    /**
     * Verifies the schema version matches.
     *
     * @param expectedVersion the expected schema version
     * @return this assertion for chaining
     */
    public EventEnvelopeAssert hasSchemaVersion(int expectedVersion) {
        isNotNull();
        if (actual.schemaVersion() != expectedVersion) {
            failWithMessage("Expected schema version <%d> but was <%d>",
                    expectedVersion, actual.schemaVersion());
        }
        return this;
    }

    /**
     * Verifies the correlation ID in the causal context matches.
     *
     * @param expectedCorrelationId the expected correlation ULID
     * @return this assertion for chaining
     */
    public EventEnvelopeAssert hasCorrelationId(Ulid expectedCorrelationId) {
        isNotNull();
        CausalContext context = actual.causalContext();
        if (context == null) {
            failWithMessage(
                    "Expected causal context with correlation ID <%s> but causal context was null",
                    expectedCorrelationId);
        } else if (!Objects.equals(context.correlationId(), expectedCorrelationId)) {
            failWithMessage("Expected correlation ID <%s> but was <%s>",
                    expectedCorrelationId, context.correlationId());
        }
        return this;
    }

    /**
     * Verifies the causation ID in the causal context matches.
     *
     * @param expectedCausationId the expected causation ULID
     * @return this assertion for chaining
     */
    public EventEnvelopeAssert hasCausationId(Ulid expectedCausationId) {
        isNotNull();
        CausalContext context = actual.causalContext();
        if (context == null) {
            failWithMessage(
                    "Expected causal context with causation ID <%s> but causal context was null",
                    expectedCausationId);
        } else if (!Objects.equals(context.causationId(), expectedCausationId)) {
            failWithMessage("Expected causation ID <%s> but was <%s>",
                    expectedCausationId, context.causationId());
        }
        return this;
    }

    /**
     * Verifies this is a root event (causation ID is null).
     *
     * @return this assertion for chaining
     */
    public EventEnvelopeAssert isRootEvent() {
        isNotNull();
        CausalContext context = actual.causalContext();
        if (context == null) {
            failWithMessage("Expected root event but causal context was null");
            return this;
        }
        if (context.causationId() != null) {
            failWithMessage(
                    "Expected root event (null causation ID) but causation ID was <%s>",
                    context.causationId());
        }
        return this;
    }

    /**
     * Verifies the subject sequence matches.
     *
     * @param expectedSequence the expected subject sequence number
     * @return this assertion for chaining
     */
    public EventEnvelopeAssert hasSubjectSequence(long expectedSequence) {
        isNotNull();
        if (actual.subjectSequence() != expectedSequence) {
            failWithMessage("Expected subject sequence <%d> but was <%d>",
                    expectedSequence, actual.subjectSequence());
        }
        return this;
    }

    /**
     * Verifies the global position matches.
     *
     * @param expectedPosition the expected global position
     * @return this assertion for chaining
     */
    public EventEnvelopeAssert hasGlobalPosition(long expectedPosition) {
        isNotNull();
        if (actual.globalPosition() != expectedPosition) {
            failWithMessage("Expected global position <%d> but was <%d>",
                    expectedPosition, actual.globalPosition());
        }
        return this;
    }

    /**
     * Verifies the payload is an instance of the expected type.
     *
     * @param expectedType the expected payload class
     * @return this assertion for chaining
     */
    public EventEnvelopeAssert hasPayloadInstanceOf(
            Class<? extends DomainEvent> expectedType) {
        isNotNull();
        if (actual.payload() == null) {
            failWithMessage("Expected payload of type <%s> but payload was null",
                    expectedType.getSimpleName());
        } else if (!expectedType.isInstance(actual.payload())) {
            failWithMessage("Expected payload of type <%s> but was <%s>",
                    expectedType.getSimpleName(),
                    actual.payload().getClass().getSimpleName());
        }
        return this;
    }

    /**
     * Verifies the subject reference matches the expected value.
     *
     * @param expectedSubjectRef the expected subject reference
     * @return this assertion for chaining
     */
    public EventEnvelopeAssert hasSubjectRef(SubjectRef expectedSubjectRef) {
        isNotNull();
        if (!Objects.equals(actual.subjectRef(), expectedSubjectRef)) {
            failWithMessage("Expected subject ref <%s> but was <%s>",
                    expectedSubjectRef, actual.subjectRef());
        }
        return this;
    }

    /**
     * Verifies actorRef is non-null.
     *
     * @return this assertion for chaining
     */
    public EventEnvelopeAssert hasNonNullActorRef() {
        isNotNull();
        if (actual.actorRef() == null) {
            failWithMessage("Expected non-null actorRef but was null");
        }
        return this;
    }

    /**
     * Verifies actorRef is null.
     *
     * @return this assertion for chaining
     */
    public EventEnvelopeAssert hasNullActorRef() {
        isNotNull();
        if (actual.actorRef() != null) {
            failWithMessage("Expected null actorRef but was <%s>", actual.actorRef());
        }
        return this;
    }

    /**
     * Verifies the ingest time is at or after the given instant.
     *
     * @param threshold the earliest acceptable ingest time
     * @return this assertion for chaining
     */
    public EventEnvelopeAssert hasIngestTimeAtOrAfter(Instant threshold) {
        isNotNull();
        if (actual.ingestTime().isBefore(threshold)) {
            failWithMessage("Expected ingest time at or after <%s> but was <%s>",
                    threshold, actual.ingestTime());
        }
        return this;
    }
}
