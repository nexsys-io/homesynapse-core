/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.observability;

import java.util.List;
import java.util.Objects;

/**
 * Tree node in the causal chain structure, wrapping a {@link TraceEvent} with its direct children.
 *
 * <p>The {@link TraceChain} contains a root {@link TraceNode} representing the
 * triggering event. Each node's children are the events caused by that event
 * (linked via {@code causationId}). The entire causal chain forms a forest
 * (typically a tree with a single root, but multiple roots are possible for
 * complex scenarios) (Doc 11 §4.2).</p>
 *
 * <p>Children are ordered by eventTime ascending. Leaf nodes (terminal events)
 * have an empty children list.</p>
 *
 * @see TraceEvent
 * @see TraceChain
 */
public record TraceNode(
    /**
     * The trace event at this node.
     *
     * <p>Non-null. Contains the event ID, type, entity reference, causal context
     * (correlationId, causationId), timestamps, and payload.</p>
     */
    TraceEvent event,

    /**
     * Direct children of this node in the causal chain.
     *
     * <p>Non-null, may be empty for leaf nodes (e.g., terminal events like
     * state_confirmed, automation_completed). Each child's {@code event.causationId}
     * equals this node's {@code event.eventId}. Ordered by eventTime ascending
     * (chronological order).</p>
     */
    List<TraceNode> children
) {
    /**
     * Compact constructor validating all fields and applying defensive copies.
     *
     * @throws NullPointerException if event or children is null
     */
    public TraceNode {
        Objects.requireNonNull(event, "event cannot be null");
        Objects.requireNonNull(children, "children cannot be null");
        children = List.copyOf(children);
    }
}
