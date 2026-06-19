/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

/**
 * The calendar-event edge a {@link CalendarTrigger} fires on (AMD-88 §2.2).
 *
 * <p>A {@code CalendarTrigger} watches a calendar-integration entity and fires
 * when one of its events starts or ends, optionally offset by a fixed duration.</p>
 *
 * <p>This enum is automation-resident and never appears in an event payload, so
 * it carries no wire-format methods (AMD-92 type-residency rule). Lower-case YAML
 * wire forms ({@code event_start}, {@code event_end}) are mapped to these
 * constants at definition-load time.</p>
 *
 * <p>Defined in AMD-88 §2.2.</p>
 *
 * @see CalendarTrigger
 */
public enum CalendarEventTransition {

    /** Fire relative to the start of the calendar event. */
    EVENT_START,

    /** Fire relative to the end of the calendar event. */
    EVENT_END
}
