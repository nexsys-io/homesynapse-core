/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.observability;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Complete causal chain for a single correlation_id, assembled by the {@link TraceQueryService}.
 *
 * <p>This is the primary data structure returned by trace queries and consumed by:
 * <ul>
 *   <li>The REST API's trace endpoints (Doc 09)</li>
 *   <li>The Web UI's trace visualization (Doc 13 §3.6.3)</li>
 *   <li>Diagnostic tools and dashboards</li>
 * </ul>
 * <p>The chain contains all events with a given {@code correlationId}, assembled
 * in multiple views (flat list and hierarchical tree) for different consumption
 * patterns (Doc 11 §3.4, §4.2).</p>
 *
 * @see TraceEvent
 * @see TraceNode
 * @see TraceCompleteness
 * @see TraceQueryService
 */
public record TraceChain(
    /**
     * The shared correlation identifier linking all events in this chain.
     *
     * <p>Non-null. Format: Crockford Base32-encoded ULID (26-char string).
     * All events in {@code orderedEvents} have this same {@code correlationId}.
     * The root event's correlationId equals its own eventId.</p>
     */
    String correlationId,

    /**
     * The event that initiated the chain.
     *
     * <p>Non-null. This is the event with no {@code causationId} (root event).
     * The chain flows outward from this event via causation links.</p>
     */
    TraceEvent rootEvent,

    /**
     * All events in the chain, ordered by timestamp ascending.
     *
     * <p>Non-null, non-empty. Contains at least the root event. All events have
     * {@code correlationId} equal to this chain's correlationId. Ordered by
     * eventTime ascending (earliest first). Used for sequential causal analysis
     * ("what happened in order?").</p>
     */
    List<TraceEvent> orderedEvents,

    /**
     * Hierarchical tree structure built from causation_id parent-child relationships.
     *
     * <p>Non-null. The root node's event equals {@code rootEvent}. Children
     * of each node are ordered by eventTime ascending. Used for hierarchical
     * visualization ("what event caused what?"). Gaps in the chain (missing
     * events due to retention) appear as broken parent-child links.</p>
     */
    TraceNode tree,

    /**
     * Whether the chain is complete, in-progress, or possibly incomplete.
     *
     * <p>Non-null. {@link TraceCompleteness.Complete} if a terminal event
     * is present. {@link TraceCompleteness.InProgress} if no terminal event
     * yet. {@link TraceCompleteness.PossiblyIncomplete} if events are missing
     * from the middle (retention purged).</p>
     */
    TraceCompleteness completeness,

    /**
     * Timestamp of the earliest event in the chain.
     *
     * <p>Non-null. Equals the root event's eventTime (assuming root is earliest).
     * Cached for convenience.</p>
     */
    Instant firstTimestamp,

    /**
     * Timestamp of the most recent event in the chain.
     *
     * <p>Non-null. Equals the eventTime of the last event in orderedEvents.
     * For complete chains, this is the terminal event's time. For in-progress
     * chains, this is the most recent awaited-completion event. Cached for
     * convenience.</p>
     */
    Instant lastTimestamp
) {
    /**
     * Compact constructor validating all fields and applying defensive copies.
     *
     * @throws NullPointerException if any field is null
     * @throws IllegalArgumentException if orderedEvents is empty
     */
    public TraceChain {
        Objects.requireNonNull(correlationId, "correlationId cannot be null");
        Objects.requireNonNull(rootEvent, "rootEvent cannot be null");
        Objects.requireNonNull(orderedEvents, "orderedEvents cannot be null");
        Objects.requireNonNull(tree, "tree cannot be null");
        Objects.requireNonNull(completeness, "completeness cannot be null");
        Objects.requireNonNull(firstTimestamp, "firstTimestamp cannot be null");
        Objects.requireNonNull(lastTimestamp, "lastTimestamp cannot be null");
        orderedEvents = List.copyOf(orderedEvents);
        if (orderedEvents.isEmpty()) {
            throw new IllegalArgumentException("orderedEvents cannot be empty");
        }
    }
}
