/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

package com.homesynapse.event;

import java.time.Instant;
import java.util.List;

import com.homesynapse.platform.identity.Ulid;

/**
 * Read-side interface for the append-only domain event store (Doc 01 §4.2, §8.1).
 *
 * <p>{@code EventStore} provides query primitives for subscribers, the REST API,
 * diagnostic tools, and the causal chain projection. All queries return events in
 * their natural ordering: {@code globalPosition} for cross-subject queries,
 * {@code subjectSequence} for per-subject queries.</p>
 *
 * <p>The {@code EventStore} does not provide write access — writes go exclusively
 * through {@link EventPublisher} (single-writer model, LTD-03). Multiple subscribers
 * and API handlers may read concurrently; implementations must be thread-safe.</p>
 *
 * <p>Schema upcasting (Doc 01 §3.10) is applied transparently at read time —
 * callers always receive events with current-version payloads. If an upcaster
 * fails for a given event, the payload is wrapped in {@link DegradedEvent} and
 * the event is still returned (lenient mode).</p>
 *
 * <p>All methods throw {@link IllegalArgumentException} for invalid parameters
 * (negative positions, zero or negative {@code maxCount}, {@code null} references).
 * Specific validation rules are documented per method.</p>
 *
 * @see EventPublisher
 * @see EventPage
 * @see EventEnvelope
 */
public interface EventStore {

    /**
     * Reads events from the global log starting after the given position.
     *
     * <p>This is the primary subscriber polling method (Doc 01 §3.4). Every
     * subscriber calls this on every poll cycle to check for new events beyond
     * its checkpoint.</p>
     *
     * @param afterPosition return events with {@code globalPosition > afterPosition};
     *                      use 0 to read from the beginning; must be {@code >= 0}
     * @param maxCount      maximum number of events to return; must be {@code >= 1}
     * @return an {@link EventPage} containing matching events in
     *         {@code globalPosition} ascending order, with {@code hasMore} indicating
     *         whether additional events exist beyond this page
     * @throws IllegalArgumentException if {@code afterPosition < 0} or
     *                                  {@code maxCount < 1}
     */
    EventPage readFrom(long afterPosition, int maxCount);

    /**
     * Reads the event stream for a specific subject.
     *
     * <p>Returns events for the given subject with
     * {@code subjectSequence > afterSequence}, ordered by {@code subjectSequence}
     * ascending. Used for entity history, replay for a specific device or entity,
     * and state projection rebuilds.</p>
     *
     * @param subject       the subject whose events to read; never {@code null}
     * @param afterSequence return events with {@code subjectSequence > afterSequence};
     *                      use 0 to read from the beginning; must be {@code >= 0}
     * @param maxCount      maximum number of events to return; must be {@code >= 1}
     * @return an {@link EventPage} containing matching events in
     *         {@code subjectSequence} ascending order
     * @throws IllegalArgumentException if {@code subject} is {@code null},
     *                                  {@code afterSequence < 0}, or
     *                                  {@code maxCount < 1}
     */
    EventPage readBySubject(SubjectRef subject, long afterSequence, int maxCount);

    /**
     * Returns all events in a causal chain, identified by correlation ID.
     *
     * <p>Returns events ordered by {@code globalPosition} ascending. No pagination
     * — causal chains are bounded in practice (Doc 01 §4.5 warns at depth 50).
     * Returns an empty list if no events match the correlation ID.</p>
     *
     * <p>Used by the causal chain projection (Doc 01 §4.5) and the trace query
     * API for assembling complete causal graphs.</p>
     *
     * @param correlationId the correlation ID identifying the causal chain;
     *                      never {@code null}
     * @return all events sharing the given correlation ID, in
     *         {@code globalPosition} ascending order; never {@code null}
     * @throws IllegalArgumentException if {@code correlationId} is {@code null}
     */
    List<EventEnvelope> readByCorrelation(Ulid correlationId);

    /**
     * Reads events of a specific type across all subjects.
     *
     * <p>Returns events with matching {@code eventType} and
     * {@code globalPosition > afterPosition}. Used by diagnostic tools and the
     * REST API's type-filtered event endpoints.</p>
     *
     * @param eventType     the event type to filter by (e.g., {@code "state_changed"});
     *                      never {@code null}
     * @param afterPosition return events with {@code globalPosition > afterPosition};
     *                      use 0 to read from the beginning; must be {@code >= 0}
     * @param maxCount      maximum number of events to return; must be {@code >= 1}
     * @return an {@link EventPage} containing matching events in
     *         {@code globalPosition} ascending order
     * @throws IllegalArgumentException if {@code eventType} is {@code null},
     *                                  {@code afterPosition < 0}, or
     *                                  {@code maxCount < 1}
     */
    EventPage readByType(String eventType, long afterPosition, int maxCount);

    /**
     * Reads events within a time range.
     *
     * <p>Uses {@code COALESCE(event_time, ingest_time)} semantics for time
     * comparison (Doc 01 §4.2 index design): if an event has an {@code eventTime},
     * that is used; otherwise {@code ingestTime} is used. Combined with
     * position-based pagination via {@code afterPosition} for consistent paging
     * through results.</p>
     *
     * <p>Used by the REST API's time-range query endpoints and retention
     * queries.</p>
     *
     * @param from          inclusive start of the time range; never {@code null}
     * @param to            exclusive end of the time range; never {@code null};
     *                      must be after {@code from}
     * @param afterPosition return events with {@code globalPosition > afterPosition}
     *                      within the time range; use 0 to start from the beginning;
     *                      must be {@code >= 0}
     * @param maxCount      maximum number of events to return; must be {@code >= 1}
     * @return an {@link EventPage} containing matching events in
     *         {@code globalPosition} ascending order within the time range
     * @throws IllegalArgumentException if {@code from} or {@code to} is {@code null},
     *                                  {@code from} is not before {@code to},
     *                                  {@code afterPosition < 0}, or
     *                                  {@code maxCount < 1}
     */
    EventPage readByTimeRange(Instant from, Instant to, long afterPosition, int maxCount);

    /**
     * Returns the current global log head position.
     *
     * <p>This is the highest {@code globalPosition} in the store. Returns 0 if the
     * store is empty. Used by subscribers to detect how far behind they are, and
     * by the REPLAY→LIVE transition logic (Doc 01 §3.7) to determine when a
     * subscriber has caught up to the log head.</p>
     *
     * @return the highest global position, or 0 if the store is empty
     */
    long latestPosition();
}
