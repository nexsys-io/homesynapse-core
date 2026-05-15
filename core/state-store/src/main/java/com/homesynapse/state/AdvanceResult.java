/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

/**
 * Result of a single {@link ProjectionAdvancer#advance} call.
 *
 * <p>The projection loop uses {@link #lastProcessedPosition()} as the
 * {@code fromPosition} parameter for the next {@code advance} call, advancing
 * through the event log in bounded chunks. {@link #hasMore()} signals whether
 * the projection has caught up to the writer's head; when {@code false}, the
 * loop may park until the next event is published.
 *
 * @param lastProcessedPosition the {@code global_position} of the last event
 *                              processed in this chunk, or the input
 *                              {@code fromPosition} if no events were available
 *                              (must be ≥ 0)
 * @param eventsProcessed       number of events processed in this chunk
 *                              ({@code 0} if caught up; must be ≥ 0)
 * @param hasMore               {@code true} if more events exist beyond this
 *                              chunk and another {@code advance} call should
 *                              be made promptly
 */
public record AdvanceResult(
        long lastProcessedPosition,
        int eventsProcessed,
        boolean hasMore
) {

    /**
     * Validates the record components.
     *
     * @throws IllegalArgumentException if either {@code lastProcessedPosition}
     *                                  or {@code eventsProcessed} is negative
     */
    public AdvanceResult {
        if (lastProcessedPosition < 0) {
            throw new IllegalArgumentException(
                    "lastProcessedPosition must be non-negative: "
                            + lastProcessedPosition);
        }
        if (eventsProcessed < 0) {
            throw new IllegalArgumentException(
                    "eventsProcessed must be non-negative: " + eventsProcessed);
        }
    }
}
