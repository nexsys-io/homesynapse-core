/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.util.Map;
import java.util.Objects;

import com.homesynapse.event.DomainEvent;

/**
 * The generic payload an {@link EmitEventAction} publishes onto the event bus — a
 * user-defined event type carrying an opaque key/value map (Doc 07 §3.9 EmitEvent).
 *
 * <p>{@link DomainEvent} is permanently non-sealed (AMD-33), so an automation-resident
 * payload is legal (the {@code automation -> event} edge already exists; no JPMS cycle).
 * Package-private — used only by {@link StandardActionExecutor}; it is not on the module's
 * exported API.</p>
 *
 * <p><strong>Replay limitation.</strong> This type carries no {@code @EventType} annotation
 * (the {@code emittedType} is user-defined, not a registered core type), so the persistence
 * registry cannot map it back on read — a persisted instance degrades to {@code DegradedEvent}
 * during replay. Write-side publication is correct; full custom-event replayability requires a
 * registered generic custom-event substrate (future work). The {@code emittedType} survives on
 * the envelope's {@code eventType} column regardless.</p>
 *
 * @param emittedType the user-defined event type, never {@code null} or blank
 * @param payload     the user-defined payload, unmodifiable (defensively copied), never
 *                    {@code null} (may be empty)
 */
record EmittedDomainEvent(String emittedType, Map<String, Object> payload) implements DomainEvent {

    /**
     * Validates required fields and defensively copies the payload.
     *
     * @throws NullPointerException     if {@code emittedType} or {@code payload} is {@code null}
     * @throws IllegalArgumentException if {@code emittedType} is blank
     */
    EmittedDomainEvent {
        Objects.requireNonNull(emittedType, "emittedType must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        if (emittedType.isBlank()) {
            throw new IllegalArgumentException("emittedType must not be blank");
        }
        payload = Map.copyOf(payload);
    }
}
