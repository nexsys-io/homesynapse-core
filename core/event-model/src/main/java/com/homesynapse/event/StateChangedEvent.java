/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import java.util.Objects;

/**
 * Payload for {@code state_changed} events — an attribute's canonical state was updated
 * (Doc 01 §4.6).
 *
 * <p>Derived event produced by the State Projection when a
 * {@link StateReportedEvent state_reported} value differs from the previously stored
 * canonical state. Every {@code state_changed} event links back to its triggering
 * {@code state_reported} event via the {@code triggeredBy} field, supporting the
 * "why did this happen?" trace query (INV-ES-06).</p>
 *
 * <p>Default priority: {@link EventPriority#NORMAL NORMAL}.</p>
 *
 * @param attributeKey the attribute that changed; never {@code null} or blank
 * @param oldValue     the previous canonical value in serialized form; never {@code null}
 * @param newValue     the new canonical value in serialized form; never {@code null}
 * @param triggeredBy  the {@link EventId} of the {@code state_reported} event that
 *                     caused this state change; never {@code null}
 * @see StateReportedEvent
 * @see StateConfirmedEvent
 * @see EventTypes#STATE_CHANGED
 */
@EventType(EventTypes.STATE_CHANGED)
public record StateChangedEvent(
        String attributeKey,
        String oldValue,
        String newValue,
        EventId triggeredBy
) implements DomainEvent {

    /**
     * Compact constructor ensuring non-null invariants.
     */
    public StateChangedEvent {
        Objects.requireNonNull(attributeKey, "attributeKey must not be null");
        Objects.requireNonNull(oldValue, "oldValue must not be null");
        Objects.requireNonNull(newValue, "newValue must not be null");
        Objects.requireNonNull(triggeredBy, "triggeredBy must not be null");
    }
}
