/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import java.util.List;
import java.util.Objects;

/**
 * A page of events returned by {@link EventStore} query methods.
 *
 * <p>All {@code EventStore} queries that return multiple events use cursor-based
 * pagination via {@code EventPage}. Callers advance through the result set by
 * passing {@link #nextPosition()} as the {@code afterPosition} argument to the
 * next query invocation, continuing while {@link #hasMore()} is {@code true}.</p>
 *
 * <p>The {@link #events()} list is defensively copied via
 * {@link List#copyOf(java.util.Collection)} to guarantee immutability after
 * construction.</p>
 *
 * @param events       the envelopes in this page, in {@code globalPosition} order
 *                     (or {@code subjectSequence} order for per-subject queries);
 *                     may be empty if no events match; never {@code null}
 * @param nextPosition the position to use as the {@code afterPosition} argument for
 *                     the subsequent query to retrieve the next page. When
 *                     {@code hasMore} is {@code false}, this is the position of the
 *                     last event in the page (or the original {@code afterPosition}
 *                     if the page is empty). Always {@code >= 0}.
 * @param hasMore      {@code true} if additional events beyond this page match the
 *                     query; callers should continue paging while this is {@code true}
 * @see EventStore
 */
public record EventPage(
        List<EventEnvelope> events,
        long nextPosition,
        boolean hasMore
) {

    /**
     * Validates page fields and defensively copies the events list.
     *
     * @throws NullPointerException     if {@code events} is {@code null}
     * @throws IllegalArgumentException if {@code nextPosition} is negative
     */
    public EventPage {
        Objects.requireNonNull(events, "events must not be null");
        events = List.copyOf(events);

        if (nextPosition < 0) {
            throw new IllegalArgumentException(
                    "nextPosition must be >= 0, got " + nextPosition);
        }
    }
}
