/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event.bus;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * Per-subscriber in-memory dead-letter queue ring (AMD-42 §3.4.5).
 *
 * <p>Holds events that failed delivery for a subscriber. The ring has a fixed
 * capacity of 1024 entries; when full, the oldest entry is evicted. M3.5b
 * adds an optional {@link PersistentDlqWriter} injection seam so that every
 * persistent-form park also flushes to durable storage via the persistence
 * module's {@code SqliteDeadLetterStore} (AMD-36). The in-memory ring remains
 * the primary path for the M3.1 supervisor, which constructs {@link DlqEntry}
 * records without the full identity context required by {@link DeadLetter}.</p>
 *
 * <p>This class is NOT thread-safe — it is only accessed from the subscriber's
 * dedicated virtual thread and the bus's internal coordination, both of which
 * are serialized by the subscriber's mode FSM.</p>
 */
final class SubscriberDlq {

    /** Maximum capacity per AMD-42 §3.4.5. */
    static final int CAPACITY = 1024;

    private final Deque<DlqEntry> ring = new ArrayDeque<>(CAPACITY);
    private final String subscriberId;
    private final PersistentDlqWriter persistentWriter;

    /**
     * Creates a new in-memory-only DLQ ring. The persistent writer is wired
     * to a no-op — failed deliveries are held only in the bounded ring.
     *
     * <p>This is the M3.1 constructor; preserved unchanged so that existing
     * call sites in {@code InProcessEventBus} continue to compile until the
     * lifecycle module is taught how to supply a real
     * {@link PersistentDlqWriter}.</p>
     */
    SubscriberDlq() {
        this("", PersistentDlqWriter.noop());
    }

    /**
     * Creates a new DLQ ring with persistent overflow support.
     *
     * <p>When a {@link DeadLetter} is parked via {@link #park(DeadLetter)},
     * the entry is recorded in the in-memory ring AND flushed to the
     * supplied {@link PersistentDlqWriter}. The legacy
     * {@link #park(DlqEntry)} path (used by the M3.1 supervisor) is
     * unaffected — its callers do not yet have the full identity context
     * required to construct a {@link DeadLetter}.</p>
     *
     * @param subscriberId     stable identifier of the subscriber owning this
     *                         DLQ; never {@code null}
     * @param persistentWriter durable storage seam; never {@code null}
     *                         (use {@link PersistentDlqWriter#noop()} when
     *                         persistent overflow is not configured)
     * @throws NullPointerException if any argument is {@code null}
     */
    SubscriberDlq(String subscriberId, PersistentDlqWriter persistentWriter) {
        this.subscriberId = Objects.requireNonNull(subscriberId, "subscriberId");
        this.persistentWriter = Objects.requireNonNull(persistentWriter, "persistentWriter");
    }

    /**
     * Parks a failed event delivery in the in-memory DLQ ring (M3.1 supervisor
     * path).
     *
     * <p>If the ring is at capacity, the oldest entry is evicted to make room.
     * This overload does NOT flush to the persistent writer — the
     * {@link DlqEntry} carries only a subset of the identity context required
     * by the persistent schema (no {@code sequence_key}, no {@code event_id}).
     * Supervisor wiring to the {@link #park(DeadLetter)} path is tracked as a
     * future enhancement; see the module's persistent-DLQ Phase 3 notes.</p>
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
     * Parks a fully-identified dead-letter in the in-memory ring AND flushes
     * it to the persistent writer.
     *
     * <p>Both writes always run: the ring keeps a recent, fast-access trace
     * for diagnostics, while the persistent writer carries the durable audit
     * trail. Persistent-writer failures propagate to the caller — the bus's
     * supervisor (when wired) decides whether to escalate.</p>
     *
     * @param deadLetter the dead-letter; never {@code null}
     */
    void park(DeadLetter deadLetter) {
        Objects.requireNonNull(deadLetter, "deadLetter");
        DlqEntry entry = new DlqEntry(
                deadLetter.eventPosition(),
                deadLetter.causeClass(),
                deadLetter.causeMessage(),
                deadLetter.attemptCount(),
                deadLetter.firstSeenAt(),
                deadLetter.lastAttemptAt());
        if (ring.size() >= CAPACITY) {
            ring.pollFirst();
        }
        ring.addLast(entry);
        persistentWriter.park(deadLetter);
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
     * Returns the subscriber identifier this DLQ belongs to. Empty string when
     * constructed via the legacy no-arg constructor.
     *
     * @return the subscriber identifier; never {@code null}
     */
    String subscriberId() {
        return subscriberId;
    }

    /**
     * Clears all entries from the in-memory ring.
     *
     * <p>Called on {@code resume()} to reset the subscriber state. The
     * persistent store is NOT cleared — durable dead-letters survive resume
     * so the operator can still inspect them.</p>
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
