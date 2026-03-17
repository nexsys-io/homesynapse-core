/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import java.util.Objects;

import com.homesynapse.platform.identity.Ulid;

/**
 * Carries causality metadata for propagation through event chains (Doc 01 §4.1, §8.3).
 *
 * <p>The causal context threads two pieces of information through an event chain:
 * the chain's root identity ({@code correlationId}) and the immediately preceding event
 * ({@code causationId}). When a subscriber processes an event and produces a derived
 * event in response, it passes the causal context to
 * {@link EventPublisher#publish(EventDraft, CausalContext)} so that the derived event
 * inherits the chain's correlation ID and records the causing event as its causation ID.</p>
 *
 * <p><strong>Actor attribution</strong> is carried on the {@link EventEnvelope#actorRef()}
 * field — not on {@code CausalContext}. This separation enables direct indexing and
 * querying of actor identity without unpacking the causal context, which is critical
 * for multi-user audit trails (INV-MU-01).</p>
 *
 * <p><strong>Root events</strong> are created via {@link #root(Ulid)}, which constructs
 * a causal context where the correlation ID is set to the new event's own event ID
 * (self-correlation, assigned at append time by the EventPublisher) and the causation
 * ID is {@code null}.</p>
 *
 * <p><strong>Derived events</strong> inherit the correlation ID from the causing event
 * and set the causation ID to the causing event's event ID. Use
 * {@link #chain(Ulid, Ulid)} to create the context for a derived event.</p>
 *
 * <p>Both fields carry raw {@link Ulid} values rather than typed wrappers. The
 * correlation and causation IDs correspond to {@link EventId} values.</p>
 *
 * <p>This record is immutable and safe to share across threads.</p>
 *
 * @param correlationId the root event's event ID, propagated unchanged through all
 *                      downstream events in the chain; never {@code null}
 * @param causationId   the immediately preceding event's event ID; {@code null} for
 *                      root events only
 * @see EventId
 * @see EventEnvelope#actorRef()
 */
public record CausalContext(
        Ulid correlationId,
        Ulid causationId
) {

    /**
     * Validates that the correlation ID is non-null. The causation ID may be
     * {@code null} for root events per its documented semantics.
     *
     * @throws NullPointerException if {@code correlationId} is {@code null}
     */
    public CausalContext {
        Objects.requireNonNull(correlationId, "correlationId must not be null");
    }

    /**
     * Creates a causal context for a root event.
     *
     * <p>Root events have no causal predecessor — they represent external stimuli
     * entering the system. The causation ID is {@code null}.</p>
     *
     * @param correlationId the correlation ID (will be the event's own event ID,
     *                      assigned at append time by the EventPublisher);
     *                      never {@code null}
     * @return a new root causal context with {@code causationId} set to {@code null}
     * @throws NullPointerException if {@code correlationId} is {@code null}
     */
    public static CausalContext root(Ulid correlationId) {
        return new CausalContext(correlationId, null);
    }

    /**
     * Creates a causal context for a derived event in an existing chain.
     *
     * <p>The correlation ID is inherited from the causing event and propagated
     * unchanged. The causation ID is set to the causing event's event ID, establishing
     * the parent-child link in the causal graph.</p>
     *
     * @param correlationId the correlation ID inherited from the causing event;
     *                      never {@code null}
     * @param causationId   the event ID of the causing event; never {@code null}
     *                      for derived events
     * @return a new derived causal context
     * @throws NullPointerException if {@code correlationId} or {@code causationId}
     *                              is {@code null}
     */
    public static CausalContext chain(Ulid correlationId, Ulid causationId) {
        Objects.requireNonNull(causationId,
                "causationId must not be null for derived events");
        return new CausalContext(correlationId, causationId);
    }

    /**
     * Returns {@code true} if this context represents a root event (no causal predecessor).
     *
     * @return {@code true} if {@code causationId} is {@code null}
     */
    public boolean isRoot() {
        return causationId == null;
    }
}
