/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventStore;
import java.util.function.Consumer;

public interface ProjectionAdvancer {

    int DEFAULT_MAX_ROWS = 500;

    /**
     * Advances a projection cursor by reading up to {@code maxRows} events from
     * {@code fromPosition} (exclusive) and synchronously invoking {@code processor}
     * for each event in {@code globalPosition}-ascending order.
     *
     * <p><b>Transaction discipline (AMD-41 §3.2.1, normative).</b> The implementation
     * MUST open a read transaction on entry, invoke {@code processor.accept(envelope)}
     * for each event read in {@code globalPosition} order, and close the read
     * transaction before this method returns. The {@code processor} executes inside
     * the read transaction; implementations of {@code processor} MUST NOT call
     * {@code EventPublisher.publish()}, MUST NOT open writes against any
     * {@code core/state-store} connection, and MUST NOT block on resources held
     * elsewhere by the calling virtual thread. Derived publishes are buffered
     * by the processor and emitted by the projection AFTER {@code advance} returns
     * (two-phase discipline).
     *
     * <p><b>Bounded-window discipline (AMD-38, preserved).</b> Implementations MUST
     * cap a single call at {@link #DEFAULT_MAX_ROWS} = 500 rows AND ≤ 2 s wall-clock,
     * even if the caller passes a larger {@code maxRows}. The bounded-window
     * contract is load-bearing for WAL checkpoint progression (see D1 WAL Pathology
     * Validation Spike, 2026-05-15).
     *
     * <p><b>Ordering.</b> Events are delivered in strict {@code globalPosition}-ascending
     * order. Gaps (which AMD-26 / AMD-27 do not produce but defence-in-depth requires)
     * are preserved — the consumer sees events as they exist in the log.
     *
     * <p><b>Exceptions from processor.</b> If {@code processor.accept} throws, the
     * exception propagates from {@code advance}, the read transaction is closed
     * (rolled back — a no-op for read tx), and the returned {@link AdvanceResult}
     * is never constructed. The caller's subscriber supervisor (AMD-42 §3.4.5)
     * handles the exception and updates the subscriber's DLQ.
     *
     * <p><b>Threading.</b> {@code advance} is single-threaded with respect to the
     * caller's virtual thread. The implementation MUST NOT spawn helper threads.
     *
     * @param fromPosition the exclusive lower bound; events with
     *                     {@code globalPosition > fromPosition} are candidates.
     * @param maxRows the maximum number of events to deliver in this call. Capped
     *                internally at {@link #DEFAULT_MAX_ROWS}.
     * @param processor the per-event consumer, invoked inside the read transaction.
     * @return the {@link AdvanceResult} describing how far the cursor advanced and
     *         whether more events remain in the log.
     * @throws IllegalArgumentException if {@code maxRows < 1} or {@code fromPosition < 0}.
     * @throws NullPointerException if {@code processor} is {@code null}.
     * @throws RuntimeException any exception thrown by {@code processor.accept} propagates.
     */
    AdvanceResult advance(long fromPosition, int maxRows, Consumer<EventEnvelope> processor);

    /**
     * Returns the production {@code DispatchingProjectionAdvancer} (Research 8
     * REC-28) over the given event store. Each {@link #advance} opens a single
     * bounded read via {@link EventStore#readFrom(long, int)} and dispatches
     * every envelope by event type to a forwarding handler (REC-28 mod C),
     * forwarding all types so {@code eventsProcessed} and the cursor advance
     * exactly as the M3.7 {@code MinimalProjectionAdvancer} did.
     *
     * <p>This is the DEC-M3-16 gateway into the package-private
     * {@code DispatchingProjectionAdvancer} and its package-private handler set
     * (REC-28 mod A + B — explicit assembly, no {@code ServiceLoader}; no
     * {@code .handlers} subpackage). Mirrors {@link StateQueryService#materialized}
     * and {@link StateCheckpointSource#stub()}.</p>
     *
     * @param eventStore the production event store (typically
     *                   {@code PersistenceFactory.eventStore()}); never
     *                   {@code null}
     * @return a new dispatching advancer over {@code eventStore}
     */
    static ProjectionAdvancer dispatching(EventStore eventStore) {
        return DispatchingProjectionAdvancer.withDefaultHandlers(eventStore);
    }
}
