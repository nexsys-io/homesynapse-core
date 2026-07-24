/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventPage;
import com.homesynapse.event.EventStore;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.platform.identity.Ulid;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Test fake {@link EventStore} for the CMD-API status endpoint tests.
 *
 * <p>Configurable through {@link #append(EventEnvelope)} (one envelope at a
 * time — the {@link FakeStateQueryService} convention).
 * {@link #readByCorrelation(Ulid)} filters the appended envelopes by their
 * causal context's correlation id, preserving append (log) order. The other
 * query methods throw {@link UnsupportedOperationException} — the status
 * endpoint reads exactly one correlation chain.</p>
 *
 * <p>Test-only — not part of the rest-api module's exported API.</p>
 */
final class FakeEventStore implements EventStore {

    private final List<EventEnvelope> envelopes = new ArrayList<>();

    FakeEventStore() {
    }

    FakeEventStore append(EventEnvelope envelope) {
        envelopes.add(envelope);
        return this;
    }

    @Override
    public EventPage readFrom(long afterPosition, int maxCount) {
        throw new UnsupportedOperationException("not used by the command endpoints");
    }

    @Override
    public EventPage readBySubject(SubjectRef subject, long afterSequence, int maxCount) {
        throw new UnsupportedOperationException("not used by the command endpoints");
    }

    @Override
    public List<EventEnvelope> readByCorrelation(Ulid correlationId) {
        List<EventEnvelope> chain = new ArrayList<>();
        for (EventEnvelope envelope : envelopes) {
            if (envelope.causalContext().correlationId().equals(correlationId)) {
                chain.add(envelope);
            }
        }
        return chain;
    }

    @Override
    public EventPage readByType(String eventType, long afterPosition, int maxCount) {
        throw new UnsupportedOperationException("not used by the command endpoints");
    }

    @Override
    public EventPage readByTimeRange(Instant from, Instant to, long afterPosition, int maxCount) {
        throw new UnsupportedOperationException("not used by the command endpoints");
    }

    @Override
    public long latestPosition() {
        return envelopes.size();
    }
}
