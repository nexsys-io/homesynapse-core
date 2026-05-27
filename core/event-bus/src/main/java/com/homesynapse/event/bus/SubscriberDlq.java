/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event.bus;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.Optional;

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
 * <p>M3.7 — every parked entry carries a {@link DlqEntry#parkedAt() parkedAt}
 * timestamp stamped by the injected {@link Clock} at park time. The
 * {@link #oldestParkedAt()} accessor surfaces this to the operational
 * {@code GET /internal/dlq} endpoint (closing the M3.6e.2 D-2 deviation).</p>
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
    private final Clock clock;

    /**
     * Creates a new DLQ ring with persistent overflow support (M3.7 —
     * {@link Clock} now mandatory for {@code parkedAt} stamping).
     *
     * <p>When a {@link DeadLetter} is parked via {@link #park(DeadLetter)},
     * the entry is recorded in the in-memory ring (with a freshly stamped
     * {@code parkedAt}) AND flushed to the supplied
     * {@link PersistentDlqWriter}. The legacy {@link #park(DlqEntry)} path
     * (used by {@link TransitionCoordinator} for synthetic onCaughtUp DLQ
     * markers) accepts a caller-built entry — that entry's {@code parkedAt}
     * value is honoured (the caller, which has its own injected clock, has
     * already stamped it).</p>
     *
     * @param subscriberId     stable identifier of the subscriber owning this
     *                         DLQ; never {@code null}
     * @param persistentWriter durable storage seam; never {@code null}
     *                         (use {@link PersistentDlqWriter#noop()} when
     *                         persistent overflow is not configured)
     * @param clock            injected clock used to stamp {@code parkedAt}
     *                         on the {@link #park(DeadLetter)} path; never
     *                         {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    SubscriberDlq(String subscriberId, PersistentDlqWriter persistentWriter, Clock clock) {
        this.subscriberId = Objects.requireNonNull(subscriberId, "subscriberId");
        this.persistentWriter = Objects.requireNonNull(persistentWriter, "persistentWriter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Parks a caller-built DLQ entry in the in-memory ring (used by
     * {@link TransitionCoordinator} for synthetic onCaughtUp markers).
     *
     * <p>If the ring is at capacity, the oldest entry is evicted to make
     * room. This overload does NOT flush to the persistent writer — the
     * {@link DlqEntry} carries only a subset of the identity context required
     * by the persistent schema (no {@code sequence_key}, no {@code event_id}).
     * Supervisor wiring to the {@link #park(DeadLetter)} path is tracked as a
     * future enhancement; see the module's persistent-DLQ Phase 3 notes.</p>
     *
     * @param entry the DLQ entry to park; never {@code null}. The entry's
     *              {@code parkedAt} field is preserved as-is — the caller
     *              owns the stamp (its own injected {@link Clock} is the
     *              source of truth for synthetic markers).
     */
    void park(DlqEntry entry) {
        Objects.requireNonNull(entry, "entry");
        if (ring.size() >= CAPACITY) {
            ring.pollFirst();
        }
        ring.addLast(entry);
    }

    /**
     * Parks a fully-identified dead-letter in the in-memory ring AND flushes
     * it to the persistent writer.
     *
     * <p>The in-memory ring entry's {@code parkedAt} is stamped from the
     * injected {@link Clock} — NOT from {@link DeadLetter#firstSeenAt()},
     * which is the supervisor's first-crash timestamp (a different
     * semantic). Both writes always run: the ring keeps a recent,
     * fast-access trace for diagnostics, while the persistent writer
     * carries the durable audit trail. Persistent-writer failures propagate
     * to the caller — the bus's supervisor decides whether to escalate.</p>
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
                deadLetter.lastAttemptAt(),
                clock.instant());
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
     * Returns the subscriber identifier this DLQ belongs to.
     *
     * @return the subscriber identifier; never {@code null}
     */
    String subscriberId() {
        return subscriberId;
    }

    /**
     * Returns the {@code parkedAt} stamp of the oldest entry in the ring, or
     * {@link Optional#empty()} if the ring is empty (M3.7).
     *
     * <p>The ring is ordered by insertion (oldest at head, newest at tail),
     * so the head IS the oldest. Capacity-driven eviction removes the
     * current head ({@link Deque#pollFirst}); the next entry becomes the
     * new oldest. This means the value reported is the oldest entry
     * <em>still in the ring</em>, NOT the all-time-oldest park timestamp
     * for this subscriber — older entries may have been evicted.</p>
     *
     * @return the oldest parked entry's stamp, empty when the ring is empty
     */
    Optional<Instant> oldestParkedAt() {
        return ring.isEmpty()
                ? Optional.empty()
                : Optional.of(ring.peekFirst().parkedAt());
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
     * Internal DLQ entry representing a single failed delivery (M3.1
     * in-memory only; M3.7 added {@code parkedAt} as the 7th field).
     *
     * @param eventPosition the global position of the failed event
     * @param causeClass    the exception class name
     * @param causeMessage  the exception message (may be {@code null})
     * @param attemptCount  the number of delivery attempts so far
     * @param firstSeenAt   when the first failure occurred
     * @param lastAttemptAt when the last delivery attempt was made
     * @param parkedAt      when the entry was parked into this ring
     *                      (M3.7 — stamped from the injected {@link Clock}
     *                      at park time, never {@code null})
     */
    record DlqEntry(
            long eventPosition,
            String causeClass,
            String causeMessage,
            int attemptCount,
            Instant firstSeenAt,
            Instant lastAttemptAt,
            Instant parkedAt
    ) {
        /**
         * Validates non-null on {@code parkedAt} — other fields preserve
         * their pre-M3.7 nullability (cause message may be null, etc.).
         *
         * @throws NullPointerException if {@code parkedAt} is {@code null}
         */
        public DlqEntry {
            Objects.requireNonNull(parkedAt, "parkedAt");
        }
    }
}
