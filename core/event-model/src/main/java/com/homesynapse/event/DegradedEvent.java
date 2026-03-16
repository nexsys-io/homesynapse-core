/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import java.util.Objects;

/**
 * Wrapper for events whose payload could not be upcast to the current schema version
 * (Doc 01 §3.10).
 *
 * <p>The upcaster pipeline operates in two modes. Core projections (State Store, Automation
 * Engine, Pending Command Ledger) run in <em>strict mode</em> — a failed upcast halts
 * processing and the event is never wrapped as a {@code DegradedEvent}. Diagnostic tools
 * (trace viewer, export utilities) run in <em>lenient mode</em>, where a failed upcast
 * produces a {@code DegradedEvent} that preserves the raw payload and failure description
 * for forensic investigation.</p>
 *
 * <p>The {@code eventType} and {@code schemaVersion} fields are copied from the original
 * event envelope so that diagnostic tools can identify what failed without needing to
 * parse the raw payload. The {@code rawPayload} is the original JSON string before the
 * upcaster attempted transformation. The {@code failureReason} describes why the upcast
 * failed (e.g., missing required field, incompatible type change).</p>
 *
 * @param eventType     the dotted event type key from the original envelope, never
 *                      {@code null} or blank
 * @param schemaVersion the schema version from the original envelope, always {@code >= 1}
 * @param rawPayload    the original JSON payload before the failed upcast attempt,
 *                      never {@code null}
 * @param failureReason a description of why the upcast failed, never {@code null}
 * @see DomainEvent
 * @see EventEnvelope
 */
public record DegradedEvent(
        String eventType,
        int schemaVersion,
        String rawPayload,
        String failureReason
) implements DomainEvent {

    /**
     * Validates all fields: none may be {@code null}, {@code eventType} must not be blank,
     * and {@code schemaVersion} must be at least 1.
     *
     * @throws NullPointerException     if any field is {@code null}
     * @throws IllegalArgumentException if {@code eventType} is blank or
     *                                  {@code schemaVersion} is less than 1
     */
    public DegradedEvent {
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(rawPayload, "rawPayload must not be null");
        Objects.requireNonNull(failureReason, "failureReason must not be null");
        if (eventType.isBlank()) {
            throw new IllegalArgumentException("eventType must not be blank");
        }
        if (schemaVersion < 1) {
            throw new IllegalArgumentException(
                    "schemaVersion must be >= 1, got " + schemaVersion);
        }
    }
}
