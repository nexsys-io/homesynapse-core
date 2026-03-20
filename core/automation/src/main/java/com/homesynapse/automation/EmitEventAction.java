/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.util.Map;
import java.util.Objects;

/**
 * Produces a custom event on the event bus.
 *
 * <p>Enables automations to generate application-level events that other automations
 * or subscribers can react to, supporting event-driven composition patterns.</p>
 *
 * <p>Defined in Doc 07 §3.9, §8.2.</p>
 *
 * @param eventType the event type string for the emitted event, never {@code null}
 * @param payload   the event payload as key-value pairs, unmodifiable,
 *                  never {@code null} (may be empty)
 * @see ActionDefinition
 * @see ActionExecutor
 */
public record EmitEventAction(
        String eventType,
        Map<String, Object> payload
) implements ActionDefinition {

    /**
     * Validates non-null fields and makes the payload map unmodifiable.
     *
     * @throws NullPointerException if any field is {@code null}
     */
    public EmitEventAction {
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        payload = Map.copyOf(payload);
    }
}
