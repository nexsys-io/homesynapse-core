/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import java.util.Objects;

import com.homesynapse.platform.identity.AutomationId;

/**
 * Diagnostic event emitted when a {@code for_duration} timer (AMD-25) expires without
 * cancellation, firing the trigger (AMD-92 row 14; Doc 07 §3.4 step 3).
 *
 * <p>On expiry the trigger proceeds with the standard evaluation procedure
 * (deduplication onward), using {@code startingEventId} as the triggering event to
 * preserve causal linkage to the state change that initiated the temporal pattern.</p>
 *
 * <p>Priority: DIAGNOSTIC. Subject: Automation.</p>
 *
 * @param automationId    the automation owning the timer, never {@code null}
 * @param triggerIndex    the zero-based trigger position, {@code >= 0}
 * @param triggerId       the stable, user-facing trigger identity, never {@code null}
 * @param startingEventId the event that started the timer, never {@code null}
 * @see DomainEvent
 * @see EventTypes#TRIGGER_DURATION_EXPIRED
 */
@EventType(EventTypes.TRIGGER_DURATION_EXPIRED)
public record TriggerDurationExpiredEvent(
        AutomationId automationId,
        int triggerIndex,
        String triggerId,
        EventId startingEventId
) implements DomainEvent {

    /**
     * Validates required fields and ranges.
     *
     * @throws NullPointerException     if any non-primitive field is {@code null}
     * @throws IllegalArgumentException if {@code triggerIndex} is negative
     */
    public TriggerDurationExpiredEvent {
        Objects.requireNonNull(automationId, "automationId must not be null");
        Objects.requireNonNull(triggerId, "triggerId must not be null");
        Objects.requireNonNull(startingEventId, "startingEventId must not be null");
        if (triggerIndex < 0) {
            throw new IllegalArgumentException("triggerIndex must be >= 0: " + triggerIndex);
        }
    }
}
