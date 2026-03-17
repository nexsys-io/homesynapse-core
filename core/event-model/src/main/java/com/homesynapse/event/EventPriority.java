/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

/**
 * Priority tier for events, governing delivery urgency and retention lifetime.
 *
 * <p>Every event in HomeSynapse carries a priority tier that determines two operational
 * characteristics (Doc 01 §3.3):</p>
 * <ul>
 *   <li><strong>Delivery urgency:</strong> How the Event Bus notifies subscribers. CRITICAL
 *       events are never coalesced. DIAGNOSTIC events may be coalesced under backpressure
 *       for non-exempt subscribers.</li>
 *   <li><strong>Retention lifetime:</strong> How long the event is retained before becoming
 *       eligible for purging by the Persistence Layer.</li>
 * </ul>
 *
 * <p><strong>Priority does not affect append-time durability.</strong> All events — regardless
 * of priority — are persisted with identical write-ahead guarantees (INV-ES-04). A
 * {@link #DIAGNOSTIC} event that is purged after seven days was still persisted with the
 * same durability guarantee as a {@link #CRITICAL} event at write time.</p>
 *
 * <p><strong>Priority assignment is static by default.</strong> Each event type in the
 * taxonomy has a default priority. Integration adapters may elevate a specific event
 * instance from {@link #DIAGNOSTIC} to {@link #NORMAL} — but not to {@link #CRITICAL},
 * and never downward.</p>
 *
 * <p><strong>Severity ordering:</strong> Each priority carries a numeric {@link #severity()}
 * value where lower numbers indicate higher severity. This provides a stable, ordinal-
 * independent comparison mechanism for subscription filtering. Use
 * {@code a.severity() <= b.severity()} to test whether priority {@code a} is at or above
 * priority {@code b}.</p>
 *
 * @see <a href="Doc 01 §3.3">Event Priority Model</a>
 */
public enum EventPriority {

    /**
     * Safety concerns, system integrity boundaries, or operational state transitions
     * that must be retained for the maximum configured period.
     *
     * <p>Examples: {@code availability_changed} (to offline), {@code command_result}
     * (timeout or failure), {@code system_started}, {@code system_stopped}.</p>
     *
     * <p>Default retention: 365 days. Never coalesced under any backpressure condition.
     * Never dropped by emergency retention. Severity: 0 (highest).</p>
     */
    CRITICAL(0),

    /**
     * Meaningful state transitions, successful command outcomes, and automation activity.
     *
     * <p>Examples: {@code state_changed}, {@code state_confirmed}, {@code command_issued},
     * {@code automation_triggered}, {@code presence_changed}, {@code config_changed}.</p>
     *
     * <p>Default retention: 90 days. Delivered individually — never coalesced.
     * Severity: 1.</p>
     */
    NORMAL(1),

    /**
     * Observability data for debugging and performance analysis.
     *
     * <p>Examples: {@code state_reported} (when no change detected),
     * {@code command_dispatched}, {@code presence_signal}, {@code telemetry_summary}.</p>
     *
     * <p>Default retention: 7 days. Under backpressure, specific DIAGNOSTIC event types
     * ({@code state_reported}, {@code presence_signal}, {@code telemetry_summary}) may
     * be coalesced per Doc 01 §3.6 for non-exempt subscribers. Severity: 2 (lowest).</p>
     */
    DIAGNOSTIC(2);

    private final int severity;

    EventPriority(int severity) {
        this.severity = severity;
    }

    /**
     * Returns the numeric severity value for this priority tier.
     *
     * <p>Lower values indicate higher severity: {@link #CRITICAL} = 0,
     * {@link #NORMAL} = 1, {@link #DIAGNOSTIC} = 2. This provides a stable,
     * ordinal-independent comparison mechanism — use
     * {@code event.priority().severity() <= filter.minimumPriority().severity()}
     * to test whether an event meets a minimum priority threshold.</p>
     *
     * @return the severity value (0 = highest, 2 = lowest)
     */
    public int severity() {
        return severity;
    }
}
