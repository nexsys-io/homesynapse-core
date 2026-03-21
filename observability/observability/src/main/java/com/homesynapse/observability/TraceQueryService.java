/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.observability;

import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.Ulid;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Assembles and queries causal chains from the EventStore.
 *
 * <p>This interface makes the {@code correlation_id} / {@code causation_id}
 * metadata on every EventEnvelope (Doc 01 §4.1) navigable, answering the
 * question "why did this happen?" with a full end-to-end causal chain
 * (Doc 11 §3.4, §8.1–§8.2). This is the mechanism that satisfies
 * INV-ES-06 (every state change is explainable through the causal chain)
 * at the query level. The competitive differentiator: no existing smart home
 * platform provides a full end-to-end causal chain query.</p>
 *
 * <p>All methods are read-only — the TraceQueryService never writes to the
 * EventStore (INV-PR-02). Results are immutable. Thread-safe.</p>
 *
 * @see TraceChain
 * @see TraceCompleteness
 * @see TraceEvent
 */
public interface TraceQueryService {
    /**
     * Assemble the full causal chain for a correlation_id.
     *
     * <p>Query pattern #1 from Doc 11 §3.4. Executes a single SQL query to
     * fetch all events with the given correlationId, then builds the hierarchical
     * tree structure in O(n) via hash-based parent-child linking.</p>
     *
     * <p>Returns empty if no events exist with the given correlationId.</p>
     *
     * @param correlationId the correlation identifier. Non-null.
     * @return {@code Optional} containing the assembled causal chain, or empty
     *         if no events match the correlationId.
     *
     * @throws NullPointerException if correlationId is null
     *
     * @see TraceChain
     */
    Optional<TraceChain> getChain(Ulid correlationId);

    /**
     * Reverse lookup: find the most recent causal chain affecting an entity.
     *
     * <p>Query pattern #2 from Doc 11 §3.4. This is the primary "why did this
     * happen?" diagnostic query. Given an entity ID, returns the causal chain
     * of the most recent event that affected that entity (subject-scoped
     * ordering by eventTime descending).</p>
     *
     * <p>Returns empty if no state-affecting events exist for the entity.</p>
     *
     * @param entityId the entity identifier. Non-null.
     * @return {@code Optional} containing the most recent causal chain involving
     *         the entity, or empty if no events affect the entity.
     *
     * @throws NullPointerException if entityId is null
     *
     * @see TraceChain
     */
    Optional<TraceChain> findRecentChain(EntityId entityId);

    /**
     * Find all chains involving an entity in a time range.
     *
     * <p>Query pattern #3 from Doc 11 §3.4. Returns all causal chains where
     * at least one event has the given entity as subject and its eventTime
     * falls within the specified range.</p>
     *
     * <p>Results are ordered by first event timestamp descending (most recent
     * chains first). May return an empty list.</p>
     *
     * @param entityId the entity identifier. Non-null.
     * @param from the start of the time range (inclusive). Non-null.
     * @param until the end of the time range (inclusive). Non-null.
     * @return an unmodifiable list of chains ordered by first event timestamp
     *         descending. Non-null, may be empty.
     *
     * @throws NullPointerException if entityId, from, or until is null
     *
     * @see TraceChain
     */
    List<TraceChain> findChains(EntityId entityId, Instant from, Instant until);

    /**
     * Find chains containing a specific event type in a time range.
     *
     * <p>Query pattern #4 from Doc 11 §3.4. Returns all causal chains that
     * contain at least one event of the specified type, with eventTime in
     * the given range.</p>
     *
     * @param eventType the dotted event type string (e.g., "device.state_changed",
     *        "automation.completed", "command.issued"). Non-null.
     * @param from the start of the time range (inclusive). Non-null.
     * @param until the end of the time range (inclusive). Non-null.
     * @return an unmodifiable list of chains. Non-null, may be empty.
     *
     * @throws NullPointerException if eventType, from, or until is null
     *
     * @see TraceChain
     */
    List<TraceChain> findChainsByType(String eventType, Instant from, Instant until);

    /**
     * Find all chains in a time range.
     *
     * <p>Query pattern #5 from Doc 11 §3.4. Returns all unique correlation IDs
     * with at least one event in the given time range, up to the specified limit.
     * Results are ordered by first event timestamp descending (most recent chains
     * first).</p>
     *
     * @param from the start of the time range (inclusive). Non-null.
     * @param until the end of the time range (inclusive). Non-null.
     * @param limit maximum number of chains to return. Must be > 0.
     * @return an unmodifiable list of chains ordered by first event timestamp
     *         descending, up to {@code limit} entries. Non-null, may be empty if
     *         no events exist in the time range.
     *
     * @throws NullPointerException if from or until is null
     * @throws IllegalArgumentException if limit <= 0
     *
     * @see TraceChain
     */
    List<TraceChain> findChainsByTimeRange(Instant from, Instant until, int limit);
}
