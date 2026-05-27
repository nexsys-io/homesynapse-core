/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.lifecycle;

import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventPage;
import com.homesynapse.event.EventStore;
import com.homesynapse.state.AdvanceResult;
import com.homesynapse.state.ProjectionAdvancer;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * M3.7 production {@link ProjectionAdvancer} that forwards every envelope to
 * the projection's per-event processor (Research 3 REC-20).
 *
 * <p>This is the minimal closure for {@code OR-M3-18}: a real, bounded-window
 * read against the {@link EventStore} replacing the M3.6d-b
 * {@code NO_OP_ADVANCER} placeholder. Each {@link #advance} invocation opens a
 * single short-lived read through {@link EventStore#readFrom(long, int)},
 * iterates the returned page in {@code globalPosition} order invoking
 * {@code processor.accept(envelope)}, and returns the page's
 * {@code nextPosition} as the new cursor.</p>
 *
 * <h2>Bounded-window discipline</h2>
 *
 * <p>{@code maxRows} is capped at {@link ProjectionAdvancer#DEFAULT_MAX_ROWS}
 * (500 rows) per the contract (AMD-38). The 2-second wall-clock bound is
 * satisfied by {@code EventStore.readFrom}'s own page-size discipline plus the
 * synchronous processor invocation that {@code StateProjection} controls.
 * No read transaction is held across {@code advance} calls — each invocation
 * is an independent short-lived read.</p>
 *
 * <h2>No derived publishes from the processor</h2>
 *
 * <p>Per AMD-41 §3.2.1, the processor MUST NOT call
 * {@code EventPublisher.publish} from inside the per-envelope callback.
 * {@code StateProjection} enforces this by buffering derived drafts and
 * publishing them only AFTER {@code advance} returns. This advancer simply
 * delivers envelopes; it does not police what the processor does.</p>
 *
 * <h2>Successor</h2>
 *
 * <p>The M4.0 replacement is {@code DispatchingProjectionAdvancer}
 * (Research 8 REC-28), which dispatches each envelope to a per-event-type
 * handler via the {@code @EventType} registry. This minimal variant just
 * forwards every envelope — letting {@code StateProjection}'s per-envelope
 * {@code applyToState} switch handle the actual dispatch.</p>
 *
 * @see ProjectionAdvancer
 * @see EventStore#readFrom(long, int)
 */
final class MinimalProjectionAdvancer implements ProjectionAdvancer {

    private final EventStore eventStore;

    /**
     * Constructs a new advancer backed by the supplied event store.
     *
     * @param eventStore the production {@link EventStore} (typically obtained
     *                   from {@code PersistenceFactory.eventStore()});
     *                   never {@code null}
     */
    MinimalProjectionAdvancer(EventStore eventStore) {
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore");
    }

    @Override
    public AdvanceResult advance(long fromPosition,
                                 int maxRows,
                                 Consumer<EventEnvelope> processor) {
        Objects.requireNonNull(processor, "processor");
        if (fromPosition < 0) {
            throw new IllegalArgumentException(
                    "fromPosition must be >= 0, got " + fromPosition);
        }
        if (maxRows < 1) {
            throw new IllegalArgumentException(
                    "maxRows must be >= 1, got " + maxRows);
        }

        int boundedMaxRows = Math.min(maxRows, DEFAULT_MAX_ROWS);
        EventPage page = eventStore.readFrom(fromPosition, boundedMaxRows);

        int processed = 0;
        long lastPosition = fromPosition;
        for (EventEnvelope envelope : page.events()) {
            processor.accept(envelope);
            processed++;
            lastPosition = envelope.globalPosition();
        }

        return new AdvanceResult(lastPosition, processed, page.hasMore());
    }
}
