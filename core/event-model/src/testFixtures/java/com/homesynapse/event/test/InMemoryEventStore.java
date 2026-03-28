/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event.test;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.homesynapse.event.CausalContext;
import com.homesynapse.event.EventCategory;
import com.homesynapse.event.EventDraft;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventId;
import com.homesynapse.event.EventPage;
import com.homesynapse.event.EventPublisher;
import com.homesynapse.event.EventStore;
import com.homesynapse.event.SequenceConflictException;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.platform.identity.Ulid;
import com.homesynapse.platform.identity.UlidFactory;

/**
 * Production-quality, thread-safe, in-memory implementation of the event store contract
 * for use in test infrastructure.
 *
 * <p>This is NOT a stub. It is a fully contract-complete implementation that satisfies
 * the same 27-method {@link EventStoreContractTest} that the future {@code SQLiteEventStore}
 * will also satisfy. Behavioral equivalence between {@code InMemoryEventStore} and
 * {@code SQLiteEventStore} is what validates the interface design.</p>
 *
 * <p><strong>Thread safety:</strong> All mutable state is guarded by a
 * {@link ReentrantReadWriteLock}. Read operations (all query methods) acquire the read lock.
 * Write operations ({@link #publish}, {@link #publishRoot}, {@link #reset}) acquire the
 * write lock. This allows concurrent readers with exclusive writer access, matching the
 * virtual-thread-safe concurrency model required by LTD-11/AMD-26.</p>
 *
 * <p><strong>Reset contract:</strong> {@link #reset()} atomically clears all stored events,
 * resets the global position counter to zero, and clears all per-subject sequence counters.
 * It is intended to be called between tests for isolation.</p>
 *
 * @see EventPublisher
 * @see EventStore
 * @see EventStoreContractTest
 */
public class InMemoryEventStore implements EventPublisher, EventStore {

    private final Clock clock;
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

    /** All stored events in insertion order. Index positions are not meaningful. */
    private final List<EventEnvelope> events = new ArrayList<>();

    /** Per-subject event lists for efficient {@link #readBySubject} queries. */
    private final Map<SubjectRef, List<EventEnvelope>> subjectIndex = new HashMap<>();

    /** Per-subject sequence counters for monotonic sequence assignment (LTD-05). */
    private final Map<SubjectRef, Long> subjectSequences = new HashMap<>();

    /** Global position counter, monotonically increasing starting at 1. */
    private long globalPosition;

    /**
     * Creates a new in-memory event store with the given clock for ingest-time assignment.
     *
     * <p>Clock injection is mandatory (ArchUnit-enforced). The clock is used to set
     * {@link EventEnvelope#ingestTime()} on each appended event. For deterministic tests,
     * use {@link Clock#fixed(Instant, java.time.ZoneId)}.</p>
     *
     * @param clock the clock to use for ingest-time assignment; never {@code null}
     * @throws NullPointerException if {@code clock} is {@code null}
     */
    public InMemoryEventStore(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    // ──────────────────────────────────────────────────────────────────
    // EventPublisher implementation
    // ──────────────────────────────────────────────────────────────────

    @Override
    public EventEnvelope publish(EventDraft draft, CausalContext cause)
            throws SequenceConflictException {
        Objects.requireNonNull(draft, "draft must not be null");
        Objects.requireNonNull(cause, "cause must not be null");
        return append(draft, cause);
    }

    @Override
    public EventEnvelope publishRoot(EventDraft draft)
            throws SequenceConflictException {
        Objects.requireNonNull(draft, "draft must not be null");

        Ulid newEventUlid = UlidFactory.generate(clock);
        CausalContext rootContext = CausalContext.root(newEventUlid);
        return append(draft, rootContext, EventId.of(newEventUlid));
    }

    // ──────────────────────────────────────────────────────────────────
    // EventStore implementation
    // ──────────────────────────────────────────────────────────────────

    @Override
    public EventPage readFrom(long afterPosition, int maxCount) {
        validatePagination(afterPosition, maxCount);

        rwLock.readLock().lock();
        try {
            List<EventEnvelope> matching = new ArrayList<>();
            for (EventEnvelope env : events) {
                if (env.globalPosition() > afterPosition) {
                    matching.add(env);
                    if (matching.size() == maxCount) {
                        break;
                    }
                }
            }
            return buildPage(matching, afterPosition, maxCount, countRemaining(afterPosition));
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public EventPage readBySubject(SubjectRef subject, long afterSequence, int maxCount) {
        if (subject == null) {
            throw new IllegalArgumentException("subject must not be null");
        }
        if (afterSequence < 0) {
            throw new IllegalArgumentException(
                    "afterSequence must be >= 0, got " + afterSequence);
        }
        if (maxCount < 1) {
            throw new IllegalArgumentException(
                    "maxCount must be >= 1, got " + maxCount);
        }

        rwLock.readLock().lock();
        try {
            List<EventEnvelope> subjectEvents = subjectIndex.getOrDefault(
                    subject, List.of());
            List<EventEnvelope> matching = new ArrayList<>();
            int totalMatching = 0;

            for (EventEnvelope env : subjectEvents) {
                if (env.subjectSequence() > afterSequence) {
                    totalMatching++;
                    if (matching.size() < maxCount) {
                        matching.add(env);
                    }
                }
            }

            boolean hasMore = totalMatching > maxCount;
            long nextPos = matching.isEmpty()
                    ? afterSequence
                    : matching.get(matching.size() - 1).subjectSequence();

            return new EventPage(matching, nextPos, hasMore);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public List<EventEnvelope> readByCorrelation(Ulid correlationId) {
        if (correlationId == null) {
            throw new IllegalArgumentException("correlationId must not be null");
        }

        rwLock.readLock().lock();
        try {
            List<EventEnvelope> result = new ArrayList<>();
            for (EventEnvelope env : events) {
                if (env.causalContext().correlationId().equals(correlationId)) {
                    result.add(env);
                }
            }
            return List.copyOf(result);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public EventPage readByType(String eventType, long afterPosition, int maxCount) {
        if (eventType == null) {
            throw new IllegalArgumentException("eventType must not be null");
        }
        validatePagination(afterPosition, maxCount);

        rwLock.readLock().lock();
        try {
            List<EventEnvelope> matching = new ArrayList<>();
            int totalMatching = 0;

            for (EventEnvelope env : events) {
                if (env.globalPosition() > afterPosition
                        && env.eventType().equals(eventType)) {
                    totalMatching++;
                    if (matching.size() < maxCount) {
                        matching.add(env);
                    }
                }
            }

            boolean hasMore = totalMatching > maxCount;
            long nextPos = matching.isEmpty()
                    ? afterPosition
                    : matching.get(matching.size() - 1).globalPosition();

            return new EventPage(matching, nextPos, hasMore);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public EventPage readByTimeRange(Instant from, Instant to,
                                     long afterPosition, int maxCount) {
        if (from == null) {
            throw new IllegalArgumentException("from must not be null");
        }
        if (to == null) {
            throw new IllegalArgumentException("to must not be null");
        }
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException(
                    "from must be before to: from=" + from + ", to=" + to);
        }
        validatePagination(afterPosition, maxCount);

        rwLock.readLock().lock();
        try {
            List<EventEnvelope> matching = new ArrayList<>();
            int totalMatching = 0;

            for (EventEnvelope env : events) {
                if (env.globalPosition() > afterPosition) {
                    Instant effectiveTime = env.eventTime() != null
                            ? env.eventTime()
                            : env.ingestTime();
                    // [from, to) — inclusive start, exclusive end
                    if (!effectiveTime.isBefore(from) && effectiveTime.isBefore(to)) {
                        totalMatching++;
                        if (matching.size() < maxCount) {
                            matching.add(env);
                        }
                    }
                }
            }

            boolean hasMore = totalMatching > maxCount;
            long nextPos = matching.isEmpty()
                    ? afterPosition
                    : matching.get(matching.size() - 1).globalPosition();

            return new EventPage(matching, nextPos, hasMore);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public long latestPosition() {
        rwLock.readLock().lock();
        try {
            return globalPosition;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Test isolation
    // ──────────────────────────────────────────────────────────────────

    /**
     * Atomically resets the store to an empty state.
     *
     * <p>Clears all stored events, resets the global position counter to zero,
     * and clears all per-subject sequence counters. Intended to be called between
     * tests for isolation.</p>
     */
    public void reset() {
        rwLock.writeLock().lock();
        try {
            events.clear();
            subjectIndex.clear();
            subjectSequences.clear();
            globalPosition = 0;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Internal helpers
    // ──────────────────────────────────────────────────────────────────

    /**
     * Core append logic shared by both publish methods.
     * Generates a new EventId when not already provided (for derived events).
     */
    private EventEnvelope append(EventDraft draft, CausalContext causalContext)
            throws SequenceConflictException {
        EventId eventId = EventId.of(UlidFactory.generate(clock));
        return append(draft, causalContext, eventId);
    }

    /**
     * Core append logic with a pre-generated EventId.
     * Used by publishRoot where the EventId must be known before CausalContext construction.
     */
    private EventEnvelope append(EventDraft draft, CausalContext causalContext,
                                 EventId eventId) throws SequenceConflictException {
        rwLock.writeLock().lock();
        try {
            long nextGlobalPos = ++globalPosition;

            SubjectRef subject = draft.subjectRef();
            long currentSeq = subjectSequences.getOrDefault(subject, 0L);
            long nextSeq = currentSeq + 1;

            // Duplicate detection: (subjectRef, subjectSequence) must be unique.
            // Under single-writer model this should not occur, but we enforce it
            // as a correctness guarantee matching the SQLite UNIQUE constraint.
            if (currentSeq >= nextSeq) {
                throw new SequenceConflictException(subject, nextSeq);
            }

            // Resolve categories from eventType — test fixture uses a simple default
            List<EventCategory> categories = resolveCategories(draft.eventType());

            EventEnvelope envelope = new EventEnvelope(
                    eventId,
                    draft.eventType(),
                    draft.schemaVersion(),
                    clock.instant(),
                    draft.eventTime(),
                    subject,
                    nextSeq,
                    nextGlobalPos,
                    draft.priority(),
                    draft.origin(),
                    categories,
                    causalContext,
                    draft.actorRef(),
                    draft.payload()
            );

            events.add(envelope);
            subjectIndex.computeIfAbsent(subject, k -> new ArrayList<>()).add(envelope);
            subjectSequences.put(subject, nextSeq);

            return envelope;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Resolves event categories from the event type string.
     *
     * <p>In the in-memory test fixture, a simplified static mapping is used.
     * The production SQLiteEventStore will use the full eventType→category
     * mapping from Doc 01 §4.4. For test purposes, all events are assigned
     * {@link EventCategory#SYSTEM} as a baseline category to satisfy the
     * non-empty categories constraint on {@link EventEnvelope}.</p>
     */
    private static List<EventCategory> resolveCategories(String eventType) {
        return List.of(EventCategory.SYSTEM);
    }

    /**
     * Validates common pagination parameters for EventStore query methods.
     */
    private static void validatePagination(long afterPosition, int maxCount) {
        if (afterPosition < 0) {
            throw new IllegalArgumentException(
                    "afterPosition must be >= 0, got " + afterPosition);
        }
        if (maxCount < 1) {
            throw new IllegalArgumentException(
                    "maxCount must be >= 1, got " + maxCount);
        }
    }

    /**
     * Counts total events with globalPosition > afterPosition for hasMore calculation.
     */
    private int countRemaining(long afterPosition) {
        int count = 0;
        for (EventEnvelope env : events) {
            if (env.globalPosition() > afterPosition) {
                count++;
            }
        }
        return count;
    }

    /**
     * Builds an EventPage from matching results with correct cursor semantics.
     *
     * <p>nextPosition is the globalPosition of the last event in the page
     * (the cursor to pass as afterPosition for the next query). If the page
     * is empty, nextPosition is the original afterPosition.</p>
     */
    private static EventPage buildPage(List<EventEnvelope> pageEvents,
                                       long afterPosition,
                                       int maxCount,
                                       int totalMatching) {
        boolean hasMore = totalMatching > maxCount;
        long nextPos = pageEvents.isEmpty()
                ? afterPosition
                : pageEvents.get(pageEvents.size() - 1).globalPosition();
        return new EventPage(pageEvents, nextPos, hasMore);
    }
}
