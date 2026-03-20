/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

package com.homesynapse.observability;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Single event within a causal chain query result.
 *
 * <p>This is a query-layer representation of an event returned by
 * {@link TraceQueryService} (Doc 11 §4.2). It uses String identifiers
 * (not typed ULID wrappers) because the trace service operates on text
 * representations from EventStore queries and presents the causal chain
 * to diagnostic and UI consumers. The trace model is a presentation/query-layer
 * concern, not a domain model concern.</p>
 *
 * @see TraceChain
 * @see TraceNode
 * @see TraceQueryService
 */
public record TraceEvent(
    /**
     * The event's unique identifier.
     *
     * <p>Non-null. Format: Crockford Base32-encoded ULID (26-char string).
     * This is the string representation suitable for presentation.</p>
     */
    String eventId,

    /**
     * Dotted event type string.
     *
     * <p>Non-null. Examples: "device.state_changed", "automation.completed",
     * "command.issued". Identifies the event payload type and semantics.</p>
     */
    String eventType,

    /**
     * The subject entity's identifier.
     *
     * <p>Non-null. Format: Crockford Base32-encoded ULID (26-char string).
     * Identifies which entity (device, automation, etc.) this event affects.
     * Used for subject-scoped queries and filtering.</p>
     */
    String entityId,

    /**
     * Shared correlation identifier across all events in the causal chain.
     *
     * <p>Non-null. Format: Crockford Base32-encoded ULID (26-char string).
     * All events with the same correlationId belong to the same causal chain.
     * The root event's correlationId equals its own eventId.</p>
     */
    String correlationId,

    /**
     * The eventId of the event that caused this event.
     *
     * <p>{@code null} for the root event of the chain. Non-null for all derived
     * events. Format: Crockford Base32-encoded ULID (26-char string).
     * Used to construct the hierarchical {@link TraceNode} tree structure.</p>
     *
     * <p><strong>IMPORTANT:</strong> This is the ONLY nullable String field in
     * the trace model. It is deliberately nullable to identify root events.</p>
     */
    String causationId,

    /**
     * When the event occurred.
     *
     * <p>Non-null. May differ from ingestTime for physical events (e.g., a device
     * state report from 5 minutes ago, ingested just now). Used for temporal
     * ordering within the causal chain.</p>
     */
    Instant eventTime,

    /**
     * When the event was appended to the event store.
     *
     * <p>Non-null. System time at the moment of durability. Always >= eventTime.
     * Used for subscription checkpoint tracking.</p>
     */
    Instant ingestTime,

    /**
     * Event-type-specific data extracted from the DomainEvent payload.
     *
     * <p>Non-null, but may be empty ({@code Map.of()}). Format: schemaless map
     * where keys are field names and values are JSON-serializable objects
     * (String, Number, Boolean, nested Map/List). No null values in the map
     * — if a field is absent, the key is not present. Phase 3 determines which
     * payload fields to include per event type for display.</p>
     *
     * <p>The payload is extracted from the EventEnvelope's DomainEvent payload
     * and is suitable for direct JSON serialization (REST API responses, trace
     * visualization in Web UI).</p>
     */
    Map<String, Object> payload
) {
    /**
     * Compact constructor validating non-null fields and applying defensive copies.
     *
     * <p>Validates all String fields except {@code causationId}, which is
     * explicitly nullable for root events. Applies defensive copy to
     * {@code payload}.</p>
     *
     * @throws NullPointerException if eventId, eventType, entityId, correlationId,
     *         eventTime, ingestTime, or payload is null
     */
    public TraceEvent {
        Objects.requireNonNull(eventId, "eventId cannot be null");
        Objects.requireNonNull(eventType, "eventType cannot be null");
        Objects.requireNonNull(entityId, "entityId cannot be null");
        Objects.requireNonNull(correlationId, "correlationId cannot be null");
        // causationId is EXPLICITLY nullable — null for the root event of the chain
        Objects.requireNonNull(eventTime, "eventTime cannot be null");
        Objects.requireNonNull(ingestTime, "ingestTime cannot be null");
        Objects.requireNonNull(payload, "payload cannot be null");
        payload = Map.copyOf(payload);
    }
}
