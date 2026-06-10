/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.config;

import com.homesynapse.event.CausalContext;
import com.homesynapse.event.EventCategory;
import com.homesynapse.event.EventDraft;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventId;
import com.homesynapse.event.EventPublisher;
import com.homesynapse.event.SequenceConflictException;
import com.homesynapse.platform.identity.Ulid;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Recording {@link EventPublisher} test double for the configuration
 * pipeline tests.
 *
 * <p>Captures every {@link EventDraft} handed to {@link #publishRoot} and
 * {@link #publish} so tests can assert on the AMD-70 publish metadata
 * (event type, priority, origin, {@code eventTime}, subject, actor). The
 * returned envelope echoes the draft fields with publisher-assigned fields
 * stubbed deterministically — no clock access, no persistence.</p>
 *
 * <p>Setting {@link #throwOnPublish} simulates a failing event store so
 * tests can verify that the observability-only
 * {@code config.validation_completed} publication never fails a load
 * (AMD-70-INV-01 — the config file, not the event log, is the source of
 * truth for configuration).</p>
 */
final class RecordingEventPublisher implements EventPublisher {

    /** Deterministic ingest stamp; the publisher double never reads a clock. */
    private static final Instant STUB_INGEST_TIME =
            Instant.parse("2026-01-01T00:00:00Z");

    final List<EventDraft> rootDrafts = new ArrayList<>();
    final List<EventDraft> chainedDrafts = new ArrayList<>();
    boolean throwOnPublish;

    /** Creates a new recording publisher. */
    RecordingEventPublisher() {
        // Explicit constructor per -Xlint:all -Werror requirement.
    }

    @Override
    public EventEnvelope publish(EventDraft draft, CausalContext cause)
            throws SequenceConflictException {
        if (throwOnPublish) {
            throw new SequenceConflictException(draft.subjectRef(), 1L);
        }
        chainedDrafts.add(draft);
        return cannedEnvelope(draft, rootDrafts.size() + chainedDrafts.size());
    }

    @Override
    public EventEnvelope publishRoot(EventDraft draft)
            throws SequenceConflictException {
        if (throwOnPublish) {
            throw new SequenceConflictException(draft.subjectRef(), 1L);
        }
        rootDrafts.add(draft);
        return cannedEnvelope(draft, rootDrafts.size() + chainedDrafts.size());
    }

    private static EventEnvelope cannedEnvelope(EventDraft draft, long position) {
        Ulid stubId = new Ulid(position, position);
        return new EventEnvelope(
                EventId.of(stubId),
                draft.eventType(),
                draft.schemaVersion(),
                STUB_INGEST_TIME,
                draft.eventTime(),
                draft.subjectRef(),
                1L,
                position,
                draft.priority(),
                draft.origin(),
                List.of(EventCategory.SYSTEM),
                CausalContext.root(stubId),
                draft.actorRef(),
                draft.payload());
    }
}
