/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.SequenceConflictException;
import com.homesynapse.event.test.InMemoryEventStore;
import com.homesynapse.event.test.TestEventFactory;
import com.homesynapse.state.test.ProjectionAdvancerContractTest;
import com.homesynapse.test.TestClock;

import java.util.List;

/**
 * Concrete {@link ProjectionAdvancerContractTest} that backs the advancer with
 * an {@link InMemoryEventStore} loaded with the supplied fixture envelopes.
 *
 * <p>The contract-test fixture constructs envelopes with hand-set
 * {@code globalPosition} values using a shared {@code SubjectRef} and a fixed
 * {@code subjectSequence == globalPosition} for each event. To get an
 * in-memory store with those exact positions, this concrete test publishes
 * the same number of events to a fresh {@code InMemoryEventStore} — the
 * store assigns positions {@code 1..N}, which matches the fixture's
 * expectations.</p>
 *
 * <p>The {@code readTxInProgress} hook delegates to
 * {@link InMemoryProjectionAdvancer#readTxInProgress()}.</p>
 */
class InMemoryProjectionAdvancerTest extends ProjectionAdvancerContractTest {

    private InMemoryProjectionAdvancer currentAdvancer;

    InMemoryProjectionAdvancerTest() {
        // Inherits the no-arg constructor contract.
    }

    @Override
    protected ProjectionAdvancer newAdvancer(List<EventEnvelope> log) {
        // Seed an in-memory event store with `log.size()` events using a
        // shared subject; the store assigns globalPositions 1..N which
        // matches the contract test's fixture expectations.
        InMemoryEventStore store = new InMemoryEventStore(TestClock.createDefault());
        if (!log.isEmpty()) {
            // Reuse the same SubjectRef for all seeded events so subject
            // sequence values match globalPosition values (1, 2, 3, ...).
            var subject = TestEventFactory.subject();
            for (int i = 0; i < log.size(); i++) {
                try {
                    store.publishRoot(TestEventFactory.draftFor(subject));
                } catch (SequenceConflictException sce) {
                    throw new AssertionError("seed sequence conflict", sce);
                }
            }
        }
        currentAdvancer = new InMemoryProjectionAdvancer(store);
        return currentAdvancer;
    }

    @Override
    protected boolean readTxInProgress() {
        return currentAdvancer != null && currentAdvancer.readTxInProgress();
    }
}
