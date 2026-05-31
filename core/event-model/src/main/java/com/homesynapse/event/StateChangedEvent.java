/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import com.homesynapse.value.AttributeValue;

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
 * <p><strong>Typed payload (AMD-52 / AMD-52-INV-01).</strong> {@code oldValue}/{@code newValue}
 * carry the reconstructed typed {@link AttributeValue} the derivation rule already computed,
 * not a serialized {@code String}. The typed payload is written at
 * {@code events.schema_version = 2}; a legacy {@code schema_version = 1} String-payload row
 * read under the typed reader degrades to a {@code DegradedEvent} (Path B, raw preserved).
 * {@code oldValue} is <strong>nullable</strong>: {@code null} means "no prior canonical value"
 * (the first-report case), replacing the former {@code ""} sentinel. {@code newValue} is
 * always non-null. No Jackson annotation is added — the {@link AttributeValue} (de)serializer
 * lives only in {@code core/persistence} (Jackson-isolation HARD RULE / ArchUnit Rule 7).</p>
 *
 * <p>Default priority: {@link EventPriority#NORMAL NORMAL}.</p>
 *
 * @param attributeKey the attribute that changed; never {@code null} or blank
 * @param oldValue     the previous canonical typed value, or {@code null} when there is no
 *                     prior value (first report)
 * @param newValue     the new canonical typed value; never {@code null}
 * @param triggeredBy  the {@link EventId} of the {@code state_reported} event that
 *                     caused this state change; never {@code null}
 * @see StateReportedEvent
 * @see StateConfirmedEvent
 * @see EventTypes#STATE_CHANGED
 * @see AttributeValue
 */
@EventType(EventTypes.STATE_CHANGED)
public record StateChangedEvent(
        String attributeKey,
        AttributeValue oldValue,
        AttributeValue newValue,
        EventId triggeredBy
) implements DomainEvent {

    /**
     * Compact constructor ensuring non-null invariants on every field except
     * {@code oldValue}, which is intentionally nullable (first report = no prior value).
     */
    public StateChangedEvent {
        Objects.requireNonNull(attributeKey, "attributeKey must not be null");
        Objects.requireNonNull(newValue, "newValue must not be null");
        Objects.requireNonNull(triggeredBy, "triggeredBy must not be null");
        // oldValue intentionally nullable — null = no prior canonical value (AMD-52 DP-6).
    }
}
