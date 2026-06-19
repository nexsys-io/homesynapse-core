/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import java.util.Objects;

import com.homesynapse.platform.identity.AutomationId;

/**
 * Diagnostic event emitted when a {@code for_duration} timer (AMD-25) is cancelled
 * before expiry (AMD-92 row 13; Doc 07 §3.4 step 2, §3.7 reload).
 *
 * <p>The {@code reason} is one of {@code "predicate_false"} (a subsequent event made
 * the trigger predicate false), {@code "definition_changed"} (a hot-reload changed the
 * trigger definition hash), or {@code "automation_removed"} (the automation was removed
 * on reload).</p>
 *
 * <p>Priority: DIAGNOSTIC. Subject: Automation.</p>
 *
 * @param automationId    the automation owning the timer, never {@code null}
 * @param triggerIndex    the zero-based trigger position, {@code >= 0}
 * @param triggerId       the stable, user-facing trigger identity, never {@code null}
 * @param startingEventId the event that started the timer, never {@code null}
 * @param reason          the cancellation reason, never {@code null} or blank
 * @see DomainEvent
 * @see EventTypes#TRIGGER_DURATION_CANCELLED
 */
@EventType(EventTypes.TRIGGER_DURATION_CANCELLED)
public record TriggerDurationCancelledEvent(
        AutomationId automationId,
        int triggerIndex,
        String triggerId,
        EventId startingEventId,
        String reason
) implements DomainEvent {

    /**
     * Validates required fields and ranges.
     *
     * @throws NullPointerException     if any non-primitive field is {@code null}
     * @throws IllegalArgumentException if {@code triggerIndex} is negative or
     *                                  {@code reason} is blank
     */
    public TriggerDurationCancelledEvent {
        Objects.requireNonNull(automationId, "automationId must not be null");
        Objects.requireNonNull(triggerId, "triggerId must not be null");
        Objects.requireNonNull(startingEventId, "startingEventId must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        if (triggerIndex < 0) {
            throw new IllegalArgumentException("triggerIndex must be >= 0: " + triggerIndex);
        }
        if (reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
    }
}
