/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import java.util.Objects;

import com.homesynapse.platform.identity.AutomationId;
import com.homesynapse.platform.identity.Ulid;

/**
 * Payload for {@code automation_disabled} events — an automation was auto-disabled after
 * repeated Run failures within the configured window (Doc 07 §6.2; AMD-92 row 10).
 *
 * <p>Emitted once when an automation's Run failures reach
 * {@code automation.auto_disable_failure_count} within
 * {@code automation.auto_disable_window_minutes}. The automation is marked disabled and
 * its triggers are suppressed until an operator re-enables it; re-enable is out of band.</p>
 *
 * <p><strong>Priority: {@link EventPriority#NORMAL NORMAL}, not DIAGNOSTIC</strong>
 * (AMD-92 R92-3, correcting the earlier §6.2 CRITICAL annotation). A disabled automation
 * is a durable operational fact the user must be able to discover weeks later — it must
 * survive the 7-day DIAGNOSTIC retention window.</p>
 *
 * <p><strong>Type residency (AMD-92-INV-01).</strong> {@code lastRunId} is a bare
 * {@link Ulid} (the automation-resident {@code RunId} is flattened). No
 * automation-resident type appears here.</p>
 *
 * @param automationId the automation that was disabled; never {@code null}
 * @param reason       the disable reason (e.g. {@code "repeated_failure"}); never
 *                     {@code null} or blank
 * @param failureCount the number of failures that triggered the disable; {@code >= 0}
 * @param windowMinutes the failure-counting window in minutes; {@code >= 0}
 * @param lastError    the most recent failure's reason; {@code null} when no failure
 *                     detail is available
 * @param lastRunId    the most recent failed Run (bare ULID — flattened {@code RunId});
 *                     {@code null} when no Run is attributable
 * @see DomainEvent
 * @see EventTypes#AUTOMATION_DISABLED
 */
@EventType(EventTypes.AUTOMATION_DISABLED)
public record AutomationDisabledEvent(
        AutomationId automationId,
        String reason,
        int failureCount,
        int windowMinutes,
        String lastError,
        Ulid lastRunId
) implements DomainEvent {

    /**
     * Validates required fields and non-negative counts. The {@code lastError} and
     * {@code lastRunId} fields are nullable.
     *
     * @throws NullPointerException     if {@code automationId} or {@code reason} is
     *                                  {@code null}
     * @throws IllegalArgumentException if {@code reason} is blank, or
     *                                  {@code failureCount} or {@code windowMinutes} is
     *                                  negative
     */
    public AutomationDisabledEvent {
        Objects.requireNonNull(automationId, "automationId must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        if (reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        if (failureCount < 0) {
            throw new IllegalArgumentException("failureCount must be >= 0: " + failureCount);
        }
        if (windowMinutes < 0) {
            throw new IllegalArgumentException("windowMinutes must be >= 0: " + windowMinutes);
        }
    }
}
