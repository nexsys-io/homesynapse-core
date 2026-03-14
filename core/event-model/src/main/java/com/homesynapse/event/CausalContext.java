/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

package com.homesynapse.event;

import java.util.Objects;

/**
 * Carries causality metadata for propagation through event chains.
 *
 * <p>The causal context is the mechanism by which the {@link CorrelationId},
 * {@link CausationId}, and {@link ActorRef} are threaded through a chain of events.
 * When a subscriber processes an event and produces a derived event in response, it
 * passes the causal context to {@code EventPublisher.publish()} so that the derived
 * event inherits the chain's correlation ID and records the causing event as its
 * causation ID (Doc 01 §8.3).</p>
 *
 * <p><strong>Root events</strong> are created via {@code EventPublisher.publishRoot()},
 * which constructs a causal context where the correlation ID is set to the new event's
 * own event ID (self-correlation) and the causation ID is {@code null}. Use
 * {@link #root(CorrelationId, ActorRef)} to create the initial context for a root event.</p>
 *
 * <p><strong>Derived events</strong> inherit the correlation ID from the causing event
 * and set the causation ID to the causing event's event ID. Use
 * {@link #chain(CorrelationId, CausationId, ActorRef)} to create the context for a
 * derived event.</p>
 *
 * <p>This record is immutable and safe to share across threads.</p>
 *
 * @param correlationId the root event's event ID, propagated unchanged through the chain;
 *                      never {@code null}
 * @param causationId   the immediately preceding event's event ID; {@code null} for root
 *                      events only
 * @param actorRef      the user attributable to this chain; {@code null} when no user is
 *                      attributable
 * @see CorrelationId
 * @see CausationId
 * @see ActorRef
 */
public record CausalContext(
        CorrelationId correlationId,
        CausationId causationId,
        ActorRef actorRef
) {

    /**
     * Validates that the correlation ID is non-null. Causation ID and actor ref may be
     * {@code null} per their documented semantics.
     *
     * @throws NullPointerException if {@code correlationId} is {@code null}
     */
    public CausalContext {
        Objects.requireNonNull(correlationId, "correlationId must not be null");
    }

    /**
     * Creates a causal context for a root event.
     *
     * <p>Root events have no causal predecessor — they represent external stimuli.
     * The causation ID is {@code null}.</p>
     *
     * @param correlationId the correlation ID (will be the event's own event ID, assigned
     *                      at append time by the EventPublisher); never {@code null}
     * @param actorRef      the user who initiated this event, or {@code null} if no user
     *                      is attributable
     * @return a new root causal context
     */
    public static CausalContext root(CorrelationId correlationId, ActorRef actorRef) {
        return new CausalContext(correlationId, null, actorRef);
    }

    /**
     * Creates a causal context for a derived event in an existing chain.
     *
     * <p>The correlation ID is inherited from the causing event. The causation ID is set
     * to the causing event's event ID.</p>
     *
     * @param correlationId the correlation ID inherited from the causing event;
     *                      never {@code null}
     * @param causationId   the event ID of the causing event; never {@code null} for
     *                      derived events
     * @param actorRef      the user attributable to this chain, or {@code null}
     * @return a new derived causal context
     * @throws NullPointerException if {@code causationId} is {@code null}
     */
    public static CausalContext chain(
            CorrelationId correlationId,
            CausationId causationId,
            ActorRef actorRef
    ) {
        Objects.requireNonNull(causationId, "causationId must not be null for derived events");
        return new CausalContext(correlationId, causationId, actorRef);
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
