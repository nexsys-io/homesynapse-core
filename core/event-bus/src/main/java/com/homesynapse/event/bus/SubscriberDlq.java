/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event.bus;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Per-subscriber in-memory dead-letter queue ring (AMD-42 §3.4.5).
 *
 * <p>Holds events that failed delivery for a subscriber. The ring has a fixed
 * capacity of 1024 entries; when full, the oldest entry is evicted. Persistent
 * overflow wiring to the {@code subscriber_dead_letters} table is deferred to
 * M3.5b.</p>
 *
 * <p>This class is NOT thread-safe — it is only accessed from the subscriber's
 * dedicated virtual thread and the bus's internal coordination, both of which
 * are serialized by the subscriber's mode FSM.</p>
 */
final class SubscriberDlq {

    /** Maximum capacity per AMD-42 §3.4.5. */
    static final int CAPACITY = 1024;

    private final Deque<DlqEntry> ring = new ArrayDeque<>(CAPACITY);

    /**
     * Creates a new empty DLQ ring.
     */
    SubscriberDlq() {
        // Package-private constructor.
    }

    /**
     * Parks a failed event delivery in the DLQ ring.
     *
     * <p>If the ring is at capacity, the oldest entry is evicted to make room.</p>
     *
     * @param entry the DLQ entry to park; never {@code null}
     */
    void park(DlqEntry entry) {
        if (ring.size() >= CAPACITY) {
            ring.pollFirst();
        }
        ring.addLast(entry);
    }

    /**
     * Returns the current number of entries in the DLQ ring.
     *
     * @return the DLQ depth (0 to {@link #CAPACITY})
     */
    int depth() {
        return ring.size();
    }

    /**
     * Clears all entries from the DLQ ring.
     *
     * <p>Called on {@code resume()} to reset the subscriber state.</p>
     */
    void clear() {
        ring.clear();
    }

    /**
     * Internal DLQ entry representing a single failed delivery (M3.1 in-memory only).
     *
     * @param eventPosition the global position of the failed event
     * @param causeClass    the exception class name
     * @param causeMessage  the exception message (may be {@code null})
     * @param attemptCount  the number of delivery attempts so far
     * @param firstSeenAt   when the first failure occurred
     * @param lastAttemptAt when the last delivery attempt was made
     */
    record DlqEntry(
            long eventPosition,
            String causeClass,
            String causeMessage,
            int attemptCount,
            Instant firstSeenAt,
            Instant lastAttemptAt
    ) {}
}
