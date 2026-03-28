/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

import com.homesynapse.platform.identity.Ulid;
import com.homesynapse.platform.identity.UlidFactory;

/**
 * Thread-safe, in-memory implementation of {@link EventPublisher} and {@link EventStore}
 * for testing.
 *
 * <p>This implementation lives in the {@code testFixtures} source set and backs the
 * {@link com.homesynapse.event.test.EventStoreContractTest}. It provides a complete,
 * production-equivalent behavioral contract without SQLite, making contract tests
 * fast and deterministic.</p>
 *
 * <p><strong>Thread safety</strong> is achieved via {@link ReentrantLock} per LTD-11
 * (no {@code synchronized} to avoid pinning virtual threads to carrier threads).
 * All mutable state is guarded by a single lock.</p>
 *
 * <p><strong>Category population:</strong> this implementation assigns
 * {@link EventCategory#SYSTEM} as the default category for all event types.
 * Production implementations will use a static {@code eventType}→category mapping
 * (Doc 01 §4.4). The contract test only asserts that categories are non-null and
 * non-empty, so this default satisfies the contract.</p>
 *
 * <p><strong>Clock injection:</strong> the constructor accepts a {@link Clock} for
 * deterministic {@code ingestTime} generation and ULID creation. Tests should use
 * {@link Clock#fixed(Instant, java.time.ZoneId)} for reproducibility.</p>
 *
 * @see EventPublisher
 * @see EventStore
 * @see com.homesynapse.event.test.EventStoreContractTest
 */
public class InMemoryEventStore implements EventPublisher, EventStore {

    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();

    // ── Mutable state (guarded by lock) ─────────────────────────────────

    /** Append-only event log in globalPosition order. */
    private final List<EventEnvelope> events = new ArrayList<>();

    /** Current highest sequence per subject. */
    private final Map<SubjectRef, Long> subjectSequences = new HashMap<>();

    /**
     * Next global position to assign. Starts at 1 because
     * {@link EventEnvelope} requires {@code globalPosition >= 0} and the
     * contract test asserts {@code globalPosition >= 1} for appended events.
     */
    private long nextGlobalPosition = 1L;

    /**
     * Creates a new in-memory event store with the given clock.
     *
     * <p>The clock is used for both {@code ingestTime} generation
     * ({@link Clock#instant()}) and ULID generation
     * ({@link UlidFactory#generate(Clock)}).</p>
     *
     * @param clock the clock for timestamps and ULID generation; never {@code null}
     * @throws NullPointerException if {@code clock} is {@code null}
     */
    public InMemoryEventStore(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    // ── EventPublisher ──────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>The provided {@link CausalContext} is placed directly into the new
     * {@link EventEnvelope} without transformation.</p>
     */
    @Override
    public EventEnvelope publish(EventDraft draft, CausalContext cause)
            throws SequenceConflictException {
        Objects.requireNonNull(draft, "draft must not be null");
        Objects.requireNonNull(cause, "cause must not be null");
        return append(draft, cause);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Constructs a root {@link CausalContext} internally using
     * {@link CausalContext#root(Ulid)} with the new event's own event ID
     * as the correlation ID (self-correlation).</p>
     */
    @Override
    public EventEnvelope publishRoot(EventDraft draft)
            throws SequenceConflictException {
        Objects.requireNonNull(draft, "draft must not be null");
        return append(draft, null);
    }

    // ── EventStore ──────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>Scans the in-memory event log for events with
     * {@code globalPosition > afterPosition}.</p>
     */
    @Override
    public EventPage readFrom(long afterPosition, int maxCount) {
        validatePositionAndCount(afterPosition, maxCount);

        lock.lock();
        try {
            List<EventEnvelope> filtered = new ArrayList<>();
            for (EventEnvelope e : events) {
                if (e.globalPosition() > afterPosition) {
                    filtered.add(e);
                }
            }
            return paginateByGlobalPosition(filtered, maxCount, afterPosition);
        } finally {
            lock.unlock();
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Scans the in-memory event log for events matching the subject with
     * {@code subjectSequence > afterSequence}. Results are naturally ordered
     * by subject sequence because events are appended sequentially.</p>
     */
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

        lock.lock();
        try {
            List<EventEnvelope> filtered = new ArrayList<>();
            for (EventEnvelope e : events) {
                if (e.subjectRef().equals(subject)
                        && e.subjectSequence() > afterSequence) {
                    filtered.add(e);
                }
            }
            return paginateBySubjectSequence(filtered, maxCount, afterSequence);
        } finally {
            lock.unlock();
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Scans the in-memory event log for events whose
     * {@link CausalContext#correlationId()} matches the given correlation ID.
     * Returns an immutable copy via {@link List#copyOf(java.util.Collection)}.</p>
     */
    @Override
    public List<EventEnvelope> readByCorrelation(Ulid correlationId) {
        if (correlationId == null) {
            throw new IllegalArgumentException("correlationId must not be null");
        }

        lock.lock();
        try {
            List<EventEnvelope> result = new ArrayList<>();
            for (EventEnvelope e : events) {
                if (e.causalContext().correlationId().equals(correlationId)) {
                    result.add(e);
                }
            }
            return List.copyOf(result);
        } finally {
            lock.unlock();
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Scans the in-memory event log for events matching the event type
     * with {@code globalPosition > afterPosition}.</p>
     */
    @Override
    public EventPage readByType(String eventType, long afterPosition, int maxCount) {
        if (eventType == null) {
            throw new IllegalArgumentException("eventType must not be null");
        }
        validatePositionAndCount(afterPosition, maxCount);

        lock.lock();
        try {
            List<EventEnvelope> filtered = new ArrayList<>();
            for (EventEnvelope e : events) {
                if (e.eventType().equals(eventType)
                        && e.globalPosition() > afterPosition) {
                    filtered.add(e);
                }
            }
            return paginateByGlobalPosition(filtered, maxCount, afterPosition);
        } finally {
            lock.unlock();
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Uses {@code COALESCE(eventTime, ingestTime)} semantics: if an event
     * has a non-null {@code eventTime}, that is used for time comparison;
     * otherwise {@code ingestTime} is used. Time range is inclusive start,
     * exclusive end. Results are further filtered by
     * {@code globalPosition > afterPosition}.</p>
     */
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
                    "from must be before to, got from=" + from + " to=" + to);
        }
        validatePositionAndCount(afterPosition, maxCount);

        lock.lock();
        try {
            List<EventEnvelope> filtered = new ArrayList<>();
            for (EventEnvelope e : events) {
                Instant effectiveTime = e.eventTime() != null
                        ? e.eventTime()
                        : e.ingestTime();
                if (e.globalPosition() > afterPosition
                        && !effectiveTime.isBefore(from)
                        && effectiveTime.isBefore(to)) {
                    filtered.add(e);
                }
            }
            return paginateByGlobalPosition(filtered, maxCount, afterPosition);
        } finally {
            lock.unlock();
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the {@code globalPosition} of the last appended event,
     * or 0 if the store is empty.</p>
     */
    @Override
    public long latestPosition() {
        lock.lock();
        try {
            if (events.isEmpty()) {
                return 0L;
            }
            return events.get(events.size() - 1).globalPosition();
        } finally {
            lock.unlock();
        }
    }

    // ── Test support ────────────────────────────────────────────────────

    /**
     * Resets the store to an empty state for test isolation.
     *
     * <p>Clears all events, subject sequence counters, and resets the global
     * position counter to 1. Called by
     * {@link com.homesynapse.event.test.EventStoreContractTest#setUp()} via
     * the subclass's {@code resetStore()} override.</p>
     */
    public void reset() {
        lock.lock();
        try {
            events.clear();
            subjectSequences.clear();
            nextGlobalPosition = 1L;
        } finally {
            lock.unlock();
        }
    }

    // ── Internal ────────────────────────────────────────────────────────

    /**
     * Core append logic shared by {@link #publish} and {@link #publishRoot}.
     *
     * <p>Generates the publisher-assigned fields ({@code eventId},
     * {@code ingestTime}, {@code subjectSequence}, {@code globalPosition},
     * {@code categories}), builds the {@link EventEnvelope}, and appends it
     * to the in-memory log.</p>
     *
     * @param draft the event draft with caller-supplied fields
     * @param cause the causal context for derived events, or {@code null}
     *              for root events (root context is built internally)
     * @return the fully-populated envelope with all 14 fields
     */
    private EventEnvelope append(EventDraft draft, CausalContext cause) {
        lock.lock();
        try {
            // Publisher-assigned fields
            EventId eventId = new EventId(UlidFactory.generate(clock));
            Instant ingestTime = clock.instant();

            // Per-subject sequence: starts at 1, increments monotonically
            long sequence = subjectSequences
                    .getOrDefault(draft.subjectRef(), 0L) + 1;
            subjectSequences.put(draft.subjectRef(), sequence);

            // Global position: monotonically increasing across all subjects
            long globalPosition = nextGlobalPosition++;

            // Causal context: use provided context for derived events,
            // build root context (self-correlation) for root events
            CausalContext causalContext = cause != null
                    ? cause
                    : CausalContext.root(eventId.value());

            // Default category for the test fixture — production implementations
            // will use a static eventType→category mapping
            List<EventCategory> categories = List.of(EventCategory.SYSTEM);

            EventEnvelope envelope = new EventEnvelope(
                    eventId,
                    draft.eventType(),
                    draft.schemaVersion(),
                    ingestTime,
                    draft.eventTime(),
                    draft.subjectRef(),
                    sequence,
                    globalPosition,
                    draft.priority(),
                    draft.origin(),
                    categories,
                    causalContext,
                    draft.actorRef(),
                    draft.payload()
            );

            events.add(envelope);
            return envelope;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Validates common position and count parameters.
     *
     * @throws IllegalArgumentException if position is negative or count is
     *                                  less than 1
     */
    private static void validatePositionAndCount(long afterPosition, int maxCount) {
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
     * Builds an {@link EventPage} from a filtered list using
     * {@code globalPosition} as the cursor.
     *
     * @param filtered         events matching the query, in globalPosition order
     * @param maxCount         maximum events per page
     * @param fallbackPosition position to use as {@code nextPosition} when
     *                         the page is empty (the original afterPosition)
     * @return a new EventPage with correct pagination metadata
     */
    private static EventPage paginateByGlobalPosition(
            List<EventEnvelope> filtered, int maxCount, long fallbackPosition) {
        if (filtered.isEmpty()) {
            return new EventPage(List.of(), fallbackPosition, false);
        }

        boolean hasMore = filtered.size() > maxCount;
        List<EventEnvelope> page = hasMore
                ? filtered.subList(0, maxCount)
                : filtered;

        long nextPosition = page.get(page.size() - 1).globalPosition();
        return new EventPage(page, nextPosition, hasMore);
    }

    /**
     * Builds an {@link EventPage} from a filtered list using
     * {@code subjectSequence} as the cursor.
     *
     * <p>Used by {@link #readBySubject} where pagination advances via
     * subject sequence rather than global position.</p>
     *
     * @param filtered          events matching the subject query, in
     *                          subjectSequence order
     * @param maxCount          maximum events per page
     * @param fallbackSequence  sequence to use as {@code nextPosition} when
     *                          the page is empty (the original afterSequence)
     * @return a new EventPage with correct pagination metadata
     */
    private static EventPage paginateBySubjectSequence(
            List<EventEnvelope> filtered, int maxCount, long fallbackSequence) {
        if (filtered.isEmpty()) {
            return new EventPage(List.of(), fallbackSequence, false);
        }

        boolean hasMore = filtered.size() > maxCount;
        List<EventEnvelope> page = hasMore
                ? filtered.subList(0, maxCount)
                : filtered;

        long nextPosition = page.get(page.size() - 1).subjectSequence();
        return new EventPage(page, nextPosition, hasMore);
    }
}
