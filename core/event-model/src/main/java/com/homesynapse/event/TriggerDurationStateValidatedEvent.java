/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import java.util.Objects;

import com.homesynapse.platform.identity.AutomationId;

/**
 * Diagnostic event emitted at {@code for_duration} timer expiry when the
 * defense-in-depth state-validation read diverges from expectation (AMD-92 row 15;
 * Doc 07 §3.4 step 4).
 *
 * <p>Published <strong>only</strong> when the validation result differs from
 * expectation — i.e. the timer was not cancelled, yet a final State Store read at
 * expiry shows the predicate is no longer true (an edge case where state changed
 * without producing a {@code state_changed} event, or the State Store was rebuilt).
 * In that case the trigger still fires because the timer was not cancelled; this event
 * records the divergence for diagnosis.</p>
 *
 * <p>Priority: DIAGNOSTIC. Subject: Automation.</p>
 *
 * @param automationId      the automation owning the timer, never {@code null}
 * @param triggerIndex      the zero-based trigger position, {@code >= 0}
 * @param triggerId         the stable, user-facing trigger identity, never {@code null}
 * @param startingEventId   the event that started the timer, never {@code null}
 * @param predicateStillTrue whether the predicate was still satisfied at the validation read
 * @see DomainEvent
 * @see EventTypes#TRIGGER_DURATION_STATE_VALIDATED
 */
@EventType(EventTypes.TRIGGER_DURATION_STATE_VALIDATED)
public record TriggerDurationStateValidatedEvent(
        AutomationId automationId,
        int triggerIndex,
        String triggerId,
        EventId startingEventId,
        boolean predicateStillTrue
) implements DomainEvent {

    /**
     * Validates required fields and ranges.
     *
     * @throws NullPointerException     if any non-primitive field is {@code null}
     * @throws IllegalArgumentException if {@code triggerIndex} is negative
     */
    public TriggerDurationStateValidatedEvent {
        Objects.requireNonNull(automationId, "automationId must not be null");
        Objects.requireNonNull(triggerId, "triggerId must not be null");
        Objects.requireNonNull(startingEventId, "startingEventId must not be null");
        if (triggerIndex < 0) {
            throw new IllegalArgumentException("triggerIndex must be >= 0: " + triggerIndex);
        }
    }
}
