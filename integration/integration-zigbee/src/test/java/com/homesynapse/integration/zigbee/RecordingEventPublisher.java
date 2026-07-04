/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import com.homesynapse.event.CausalContext;
import com.homesynapse.event.EventCategory;
import com.homesynapse.event.EventDraft;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventId;
import com.homesynapse.event.EventPublisher;
import com.homesynapse.platform.identity.UlidFactory;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * A recording {@link EventPublisher} fake for the ingestion/adoption tests:
 * captures every draft and wraps it in a minimally valid envelope (root
 * causality, DEVICE_STATE category, sequential positions). Kept local to this
 * module's test tree so the A3 build-diff pin (exactly one production
 * dependency line) holds — no testFixtures edge is added.
 */
final class RecordingEventPublisher implements EventPublisher {

    private final Clock clock;
    private final List<EventEnvelope> published = new ArrayList<>();
    private long position;

    RecordingEventPublisher(Clock clock) {
        this.clock = clock;
    }

    @Override
    public EventEnvelope publish(EventDraft draft, CausalContext cause) {
        return record(draft, cause);
    }

    @Override
    public EventEnvelope publishRoot(EventDraft draft) {
        return record(draft, null);
    }

    /** Returns every published envelope, in publish order. */
    List<EventEnvelope> published() {
        return List.copyOf(published);
    }

    /** Returns the published envelopes of one event type, in publish order. */
    Stream<EventEnvelope> ofType(String eventType) {
        return published.stream().filter(e -> e.eventType().equals(eventType));
    }

    private EventEnvelope record(EventDraft draft, CausalContext cause) {
        EventId eventId = new EventId(UlidFactory.generate(clock));
        position++;
        EventEnvelope envelope = new EventEnvelope(
                eventId,
                draft.eventType(),
                draft.schemaVersion(),
                clock.instant(),
                draft.eventTime(),
                draft.subjectRef(),
                position,
                position,
                draft.priority(),
                draft.origin(),
                List.of(EventCategory.DEVICE_STATE),
                cause != null ? cause : CausalContext.root(eventId.value()),
                draft.actorRef(),
                draft.payload());
        published.add(envelope);
        return envelope;
    }
}
