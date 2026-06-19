/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import java.util.Objects;

import com.homesynapse.platform.identity.AutomationId;
import com.homesynapse.platform.identity.EntityId;

/**
 * Diagnostic event emitted when a {@code for_duration} timer (AMD-25) starts
 * (AMD-92 row 12; Doc 07 §3.4 step 1).
 *
 * <p>The timer is keyed positionally by {@code (automationId, triggerIndex)}; the
 * user-facing {@code triggerId} (AMD-88) is carried for trace stability across
 * definition edits.</p>
 *
 * <p>Priority: DIAGNOSTIC. Subject: Automation.</p>
 *
 * @param automationId    the automation owning the timer, never {@code null}
 * @param triggerIndex    the zero-based trigger position, {@code >= 0}
 * @param triggerId       the stable, user-facing trigger identity, never {@code null}
 * @param startingEventId the event that started the timer (used for deduplication on
 *                        expiry), never {@code null}
 * @param entityRef       the monitored entity, never {@code null}
 * @param forDurationMs   the required sustained duration in milliseconds, {@code >= 0}
 * @see DomainEvent
 * @see EventTypes#TRIGGER_DURATION_STARTED
 */
@EventType(EventTypes.TRIGGER_DURATION_STARTED)
public record TriggerDurationStartedEvent(
        AutomationId automationId,
        int triggerIndex,
        String triggerId,
        EventId startingEventId,
        EntityId entityRef,
        long forDurationMs
) implements DomainEvent {

    /**
     * Validates required fields and ranges.
     *
     * @throws NullPointerException     if any non-primitive field is {@code null}
     * @throws IllegalArgumentException if {@code triggerIndex} or {@code forDurationMs}
     *                                  is negative
     */
    public TriggerDurationStartedEvent {
        Objects.requireNonNull(automationId, "automationId must not be null");
        Objects.requireNonNull(triggerId, "triggerId must not be null");
        Objects.requireNonNull(startingEventId, "startingEventId must not be null");
        Objects.requireNonNull(entityRef, "entityRef must not be null");
        if (triggerIndex < 0) {
            throw new IllegalArgumentException("triggerIndex must be >= 0: " + triggerIndex);
        }
        if (forDurationMs < 0) {
            throw new IllegalArgumentException("forDurationMs must be >= 0: " + forDurationMs);
        }
    }
}
