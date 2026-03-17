/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

/**
 * The sole write path into the domain event store (Doc 01 §8.3).
 *
 * <p>{@code EventPublisher} enforces the single-writer model (LTD-03): all event
 * appends flow through this interface, serialized by a single writer thread.
 * No other component writes directly to the event store.</p>
 *
 * <p>The two-method API enforces causality at compile time. Callers cannot
 * accidentally produce a derived event without a causal context, and cannot
 * produce a root event with one:</p>
 *
 * <ul>
 *   <li>{@link #publish(EventDraft, CausalContext)} — for derived events that are
 *       caused by a prior event. The caller constructs the new event's
 *       {@link CausalContext} using {@link CausalContext#chain(com.homesynapse.platform.identity.Ulid, com.homesynapse.platform.identity.Ulid)}
 *       with values extracted from the causing {@link EventEnvelope}. The caller
 *       sets {@link EventDraft#actorRef()} by inheriting from the causing event's
 *       {@link EventEnvelope#actorRef()}.</li>
 *   <li>{@link #publishRoot(EventDraft)} — for root events representing external
 *       stimuli with no prior event cause. The publisher constructs the root
 *       {@link CausalContext} internally using {@link CausalContext#root(com.homesynapse.platform.identity.Ulid)}
 *       because the correlation ID is the new event's own event ID, which only the
 *       publisher knows at append time. The caller sets
 *       {@link EventDraft#actorRef()} to identify the initiating user (or
 *       {@code null} for system/autonomous events).</li>
 * </ul>
 *
 * <p>Both methods are synchronous from the caller's perspective — the event is
 * persisted to SQLite WAL and the method returns only after the WAL commit
 * (INV-ES-04, LTD-06). Subscriber notification begins asynchronously after
 * the method returns.</p>
 *
 * <p>The publisher is responsible for generating and assigning all
 * infrastructure-owned fields not present in the {@link EventDraft}:
 * {@code eventId} (monotonic ULID), {@code ingestTime} (system clock),
 * {@code subjectSequence} (next per-subject sequence), {@code globalPosition}
 * (SQLite rowid), and {@code categories} (static eventType→category lookup).
 * The publisher copies {@link EventDraft#actorRef()} to the new
 * {@link EventEnvelope#actorRef()} field.</p>
 *
 * <p>Thread-safe: the single-writer model means only one thread invokes the publisher
 * at a time, but implementations must be safe for concurrent access from the caller's
 * perspective (e.g., multiple virtual threads may hold a reference and call methods
 * sequentially).</p>
 *
 * @see EventDraft
 * @see EventEnvelope
 * @see CausalContext
 * @see EventStore
 */
public interface EventPublisher {

    /**
     * Appends a derived event to the domain event store.
     *
     * <p>Use this method when producing an event that is caused by a prior event
     * in an existing causal chain. The caller constructs the new event's
     * {@link CausalContext} by calling
     * {@link CausalContext#chain(com.homesynapse.platform.identity.Ulid, com.homesynapse.platform.identity.Ulid)} with:</p>
     *
     * <ul>
     *   <li>{@code correlationId} — inherited from the causing event's
     *       {@link EventEnvelope#causalContext()}</li>
     *   <li>{@code causationId} — the causing event's
     *       {@link EventEnvelope#eventId()} (the event ID, not the causation ID)</li>
     * </ul>
     *
     * <p>The caller must also set {@link EventDraft#actorRef()} by inheriting from
     * the causing event's {@link EventEnvelope#actorRef()}.</p>
     *
     * <p>The publisher places the provided {@code CausalContext} directly into the
     * new {@link EventEnvelope} without transformation, and copies
     * {@link EventDraft#actorRef()} to {@link EventEnvelope#actorRef()}.</p>
     *
     * <p>The event is durable in SQLite WAL before this method returns.
     * Subscriber notification begins asynchronously after return.</p>
     *
     * @param draft the caller-provided event metadata and payload; never {@code null}
     * @param cause the pre-built causal context for the new event, constructed via
     *              {@link CausalContext#chain(com.homesynapse.platform.identity.Ulid, com.homesynapse.platform.identity.Ulid)};
     *              never {@code null}
     * @return the fully-populated {@link EventEnvelope} with all 14 fields assigned,
     *         including publisher-generated {@code eventId}, {@code ingestTime},
     *         {@code subjectSequence}, {@code globalPosition}, and {@code categories}
     * @throws SequenceConflictException if the ({@code subjectRef},
     *         {@code subjectSequence}) unique constraint is violated (Doc 01 §6.7)
     * @throws NullPointerException if {@code draft} or {@code cause} is {@code null}
     * @see CausalContext#chain(com.homesynapse.platform.identity.Ulid, com.homesynapse.platform.identity.Ulid)
     */
    EventEnvelope publish(EventDraft draft, CausalContext cause)
            throws SequenceConflictException;

    /**
     * Appends a root event to the domain event store.
     *
     * <p>Use this method when producing an event that represents an external stimulus
     * entering the system with no prior event cause — for example, a device report
     * arriving from an integration adapter, a user command from the REST API, or a
     * system lifecycle event.</p>
     *
     * <p>The publisher constructs the root {@link CausalContext} internally:
     * {@link CausalContext#root(com.homesynapse.platform.identity.Ulid)} with the new
     * event's own event ID as the correlation ID (self-correlation). The actor
     * attribution is taken from {@link EventDraft#actorRef()} and placed on
     * {@link EventEnvelope#actorRef()}.</p>
     *
     * <p>The event is durable in SQLite WAL before this method returns.
     * Subscriber notification begins asynchronously after return.</p>
     *
     * @param draft the caller-provided event metadata and payload, including
     *              {@link EventDraft#actorRef()} for actor attribution; never
     *              {@code null}
     * @return the fully-populated {@link EventEnvelope} with all 14 fields assigned,
     *         including publisher-generated {@code eventId}, {@code ingestTime},
     *         {@code subjectSequence}, {@code globalPosition}, and {@code categories}
     * @throws SequenceConflictException if the ({@code subjectRef},
     *         {@code subjectSequence}) unique constraint is violated (Doc 01 §6.7)
     * @throws NullPointerException if {@code draft} is {@code null}
     */
    EventEnvelope publishRoot(EventDraft draft)
            throws SequenceConflictException;
}
