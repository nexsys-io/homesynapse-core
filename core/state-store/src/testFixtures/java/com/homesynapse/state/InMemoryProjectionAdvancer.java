/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventPage;
import com.homesynapse.event.EventStore;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * In-memory {@link ProjectionAdvancer} backed by an {@link EventStore}.
 *
 * <p>Wraps an {@link EventStore} (typically an
 * {@code com.homesynapse.event.test.InMemoryEventStore}) and exposes the
 * {@link ProjectionAdvancer} contract. Used by {@link StateProjection}'s
 * batch path and by the {@code ProjectionAdvancerContractTest} concrete
 * subclass.</p>
 *
 * <h2>Read-transaction simulation</h2>
 *
 * <p>The advancer maintains an internal {@link AtomicBoolean} flag indicating
 * whether the read transaction is currently open. Tests inspect this via
 * {@link #readTxInProgress()} to verify AMD-41 §3.2.1: the processor runs
 * inside the read tx, and the tx closes before {@code advance} returns. The
 * flag is set to {@code true} on {@code advance} entry and {@code false} in
 * the {@code finally} block — propagating processor exceptions still close
 * the tx.</p>
 *
 * <h2>Bounded-window discipline (AMD-38)</h2>
 *
 * <p>Caps {@code maxRows} at {@link ProjectionAdvancer#DEFAULT_MAX_ROWS} = 500.
 * The 2-second wall-clock cap is not enforced for in-memory reads (which are
 * effectively instantaneous); the production SQLite implementation will enforce
 * both bounds.</p>
 */
public final class InMemoryProjectionAdvancer implements ProjectionAdvancer {

    private final EventStore eventStore;
    private final AtomicBoolean txOpen = new AtomicBoolean(false);

    /**
     * Constructs an advancer over the given event store.
     *
     * @param eventStore the backing event store; never {@code null}
     * @throws NullPointerException if {@code eventStore} is {@code null}
     */
    public InMemoryProjectionAdvancer(EventStore eventStore) {
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore must not be null");
    }

    @Override
    public AdvanceResult advance(long fromPosition, int maxRows,
                                 Consumer<EventEnvelope> processor) {
        if (fromPosition < 0) {
            throw new IllegalArgumentException(
                    "fromPosition must be >= 0, got " + fromPosition);
        }
        if (maxRows < 1) {
            throw new IllegalArgumentException(
                    "maxRows must be >= 1, got " + maxRows);
        }
        Objects.requireNonNull(processor, "processor must not be null");

        int cappedMax = Math.min(maxRows, DEFAULT_MAX_ROWS);

        txOpen.set(true);
        try {
            EventPage page = eventStore.readFrom(fromPosition, cappedMax);
            int processed = 0;
            long lastPos = fromPosition;
            for (EventEnvelope env : page.events()) {
                processor.accept(env);
                processed++;
                lastPos = env.globalPosition();
            }
            return new AdvanceResult(lastPos, processed, page.hasMore());
        } finally {
            txOpen.set(false);
        }
    }

    /**
     * Returns whether the read transaction is currently open. Used by the
     * {@code ProjectionAdvancerContractTest} concrete subclass to satisfy the
     * abstract {@code readTxInProgress()} hook.
     *
     * @return {@code true} if the tx flag is currently set
     */
    public boolean readTxInProgress() {
        return txOpen.get();
    }
}
