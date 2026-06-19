/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import java.util.Objects;

import com.homesynapse.platform.identity.AutomationId;

/**
 * Diagnostic event emitted when starting a {@code for_duration} timer would exceed the
 * configured concurrent-timer ceiling, so the timer is rejected (AMD-92 row 16;
 * Doc 07 §9).
 *
 * <p>Identifies the specific rejected trigger (via {@code automationId} /
 * {@code triggerIndex} / {@code triggerId}, the latter added by AMD-92 E92-1 for
 * {@code trigger_duration_*} family consistency) alongside the active-count and the
 * ceiling ({@code automation.trigger.max_concurrent_duration_timers}).</p>
 *
 * <p>Priority: DIAGNOSTIC. Subject: Automation.</p>
 *
 * @param automationId               the automation whose timer was rejected, never {@code null}
 * @param triggerIndex               the zero-based trigger position, {@code >= 0}
 * @param triggerId                  the stable, user-facing trigger identity, never {@code null}
 * @param activeTimerCount           the active duration-timer count at rejection, {@code >= 0}
 * @param maxConcurrentDurationTimers the configured ceiling, {@code >= 0}
 * @see DomainEvent
 * @see EventTypes#TRIGGER_DURATION_LIMIT_EXCEEDED
 */
@EventType(EventTypes.TRIGGER_DURATION_LIMIT_EXCEEDED)
public record TriggerDurationLimitExceededEvent(
        AutomationId automationId,
        int triggerIndex,
        String triggerId,
        int activeTimerCount,
        int maxConcurrentDurationTimers
) implements DomainEvent {

    /**
     * Validates required fields and ranges.
     *
     * @throws NullPointerException     if any non-primitive field is {@code null}
     * @throws IllegalArgumentException if any count or index is negative
     */
    public TriggerDurationLimitExceededEvent {
        Objects.requireNonNull(automationId, "automationId must not be null");
        Objects.requireNonNull(triggerId, "triggerId must not be null");
        if (triggerIndex < 0) {
            throw new IllegalArgumentException("triggerIndex must be >= 0: " + triggerIndex);
        }
        if (activeTimerCount < 0) {
            throw new IllegalArgumentException(
                    "activeTimerCount must be >= 0: " + activeTimerCount);
        }
        if (maxConcurrentDurationTimers < 0) {
            throw new IllegalArgumentException(
                    "maxConcurrentDurationTimers must be >= 0: " + maxConcurrentDurationTimers);
        }
    }
}
