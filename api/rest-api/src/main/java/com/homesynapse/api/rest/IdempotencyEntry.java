/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import java.time.Instant;
import java.util.Objects;

/**
 * Entry in the in-memory idempotency LRU cache for command deduplication.
 *
 * <p>When a command request includes an {@code Idempotency-Key} header, the API
 * checks this cache before validation (AMD-08, Doc 09 §3.4). The cache behavior
 * is:</p>
 * <ul>
 *   <li>If the key exists with the same request body, the cached response is replayed
 *       without publishing a new event.</li>
 *   <li>If the key exists with a DIFFERENT request body, {@code 409 Conflict} with
 *       {@link ProblemType#IDEMPOTENCY_KEY_CONFLICT} is returned.</li>
 *   <li>If the key does not exist, the command proceeds normally and an entry is
 *       created.</li>
 * </ul>
 *
 * <p>Cache eviction semantics (Doc 09 §9):</p>
 * <ul>
 *   <li>Maximum 10,000 entries (LRU eviction).</li>
 *   <li>24-hour TTL per entry, calculated from {@link #createdAt()}.</li>
 *   <li>The cache is not persisted — it is lost on process restart. Post-restart
 *       retries with a stale key simply issue a new command. The device handles
 *       duplicate commands gracefully via the four-phase lifecycle.</li>
 * </ul>
 *
 * <p>Thread-safe (immutable record).</p>
 *
 * @param idempotencyKey client-provided key (max 128 characters), never {@code null}
 * @param commandId      the {@code command_issued} event's {@code event_id},
 *                       never {@code null}
 * @param correlationId  the event's {@code correlation_id}, never {@code null}
 * @param viewPosition   the event store position at which the command was persisted
 * @param createdAt      timestamp for TTL calculation (24-hour expiry),
 *                       never {@code null}
 *
 * @see ProblemType#IDEMPOTENCY_KEY_CONFLICT
 * @see CommandAcceptedResponse
 * @see <a href="Doc 09 §3.4">Idempotency (AMD-08)</a>
 */
public record IdempotencyEntry(
        String idempotencyKey,
        String commandId,
        String correlationId,
        long viewPosition,
        Instant createdAt) {

    /**
     * Creates a new idempotency entry with validation of required fields.
     *
     * @throws NullPointerException if any non-primitive parameter is {@code null}
     */
    public IdempotencyEntry {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Objects.requireNonNull(commandId, "commandId must not be null");
        Objects.requireNonNull(correlationId, "correlationId must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}
