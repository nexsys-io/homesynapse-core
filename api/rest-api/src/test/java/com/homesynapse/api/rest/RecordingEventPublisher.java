/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import com.homesynapse.event.CausalContext;
import com.homesynapse.event.EventCategory;
import com.homesynapse.event.EventDraft;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventId;
import com.homesynapse.event.EventPublisher;
import com.homesynapse.platform.identity.Ulid;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Test recording {@link EventPublisher} for the CMD-API endpoint tests.
 *
 * <p>Captures every {@link #publishRoot(EventDraft)} draft and returns a
 * deterministic envelope (sequential event ids and global positions, ingest
 * time from the injected clock, root causal context). The recorded drafts
 * let tests pin the exact published shape; the recorded count proves the
 * zero-second-publish idempotency-replay contract.</p>
 *
 * <p>{@code publish(...)} (the chained form) is unsupported — the command
 * write surface publishes root events only (Doc 09 §3.4).</p>
 *
 * <p>Test-only — not part of the rest-api module's exported API.</p>
 */
final class RecordingEventPublisher implements EventPublisher {

    /** Every draft passed to {@link #publishRoot(EventDraft)}, in order. */
    final List<EventDraft> rootDrafts = new ArrayList<>();

    /** Every envelope returned from {@link #publishRoot(EventDraft)}, in order. */
    final List<EventEnvelope> published = new ArrayList<>();

    private final Clock clock;
    private long nextGlobalPosition;
    private int nextIdSuffix;

    /**
     * Constructs a recorder whose envelopes start at global position 100.
     *
     * @param clock supplies the deterministic {@code ingestTime}; never {@code null}
     */
    RecordingEventPublisher(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.nextGlobalPosition = 100L;
        this.nextIdSuffix = 0;
    }

    @Override
    public EventEnvelope publish(EventDraft draft, CausalContext cause) {
        throw new UnsupportedOperationException(
                "The command write surface publishes root events only");
    }

    @Override
    public EventEnvelope publishRoot(EventDraft draft) {
        Objects.requireNonNull(draft, "draft");
        nextIdSuffix++;
        EventId eventId = new EventId(
                Ulid.parse(String.format("01H8CMD00000000000000%05d", nextIdSuffix)));
        EventEnvelope envelope = new EventEnvelope(
                eventId,
                draft.eventType(),
                draft.schemaVersion(),
                clock.instant(),
                draft.eventTime(),
                draft.subjectRef(),
                1L,
                nextGlobalPosition++,
                draft.priority(),
                draft.origin(),
                List.of(EventCategory.DEVICE_STATE),
                CausalContext.root(eventId.value()),
                draft.actorRef(),
                draft.payload());
        rootDrafts.add(draft);
        published.add(envelope);
        return envelope;
    }
}
