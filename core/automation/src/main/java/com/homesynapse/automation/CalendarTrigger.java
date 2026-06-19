/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.time.Duration;
import java.util.Objects;

import com.homesynapse.platform.identity.EntityId;

/**
 * Tier 1 trigger that fires when a calendar-integration entity's event starts or
 * ends (AMD-88 §2.2).
 *
 * <p>The {@code offset} shifts the fire time relative to the {@code transition}:
 * a {@code null} offset fires at the transition; a <strong>negative</strong> offset
 * fires {@code |offset|} BEFORE the transition; a <strong>positive</strong> offset
 * fires {@code offset} AFTER the transition (AMD-88 §2.2, E88-2 sign convention).</p>
 *
 * <p>This trigger has NO {@code for_duration} — calendar transitions are inherently
 * instantaneous (the AMD-25 {@code EventTrigger} class). No calendar integration
 * ships in the MVP; the permit freezes the YAML-visible shape now and the evaluator
 * registers the route but matches nothing until a calendar-capable integration
 * produces the consumed events (M10) — a benign no-match, not a Tier-2 fallback.</p>
 *
 * <p>Defined in AMD-88 §2.2; Doc 07 §3.4, §8.2.</p>
 *
 * @param calendarEntityId the calendar entity to watch, never {@code null}
 * @param transition       the calendar-event edge to fire on, never {@code null}
 * @param offset           the fire-time offset relative to the transition
 *                         (negative = before, positive = after); {@code null} means
 *                         fire at the transition
 * @param triggerId        the stable, user-facing trigger identity (AMD-88 §2.5),
 *                         never {@code null}
 * @see TriggerDefinition
 * @see CalendarEventTransition
 */
public record CalendarTrigger(
        EntityId calendarEntityId,
        CalendarEventTransition transition,
        Duration offset,
        String triggerId
) implements TriggerDefinition {

    /**
     * Validates non-null fields. The {@code offset} is intentionally nullable.
     *
     * @throws NullPointerException if {@code calendarEntityId}, {@code transition},
     *                              or {@code triggerId} is {@code null}
     */
    public CalendarTrigger {
        Objects.requireNonNull(calendarEntityId, "calendarEntityId must not be null");
        Objects.requireNonNull(transition, "transition must not be null");
        Objects.requireNonNull(triggerId, "triggerId must not be null");
    }
}
