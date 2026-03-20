/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.util.List;

import com.homesynapse.event.EventEnvelope;
import com.homesynapse.platform.identity.AutomationId;

/**
 * Evaluates incoming events against registered trigger definitions.
 *
 * <p>Maintains a trigger index for O(1) event-type-to-automation lookup. When an
 * event arrives, the evaluator checks it against all registered triggers for that
 * event type and returns the IDs of automations whose triggers matched.</p>
 *
 * <p>Manages duration timers for {@code for_duration} triggers (AMD-25). When a
 * trigger with {@code forDuration} matches, a {@link DurationTimer} is started
 * instead of immediately firing. The trigger fires only if the condition remains
 * continuously true for the specified duration.</p>
 *
 * <p>Thread-safe. All methods may be called concurrently from multiple virtual threads.</p>
 *
 * <p>Defined in Doc 07 §3.4, §8.1.</p>
 *
 * @see AutomationRegistry
 * @see DurationTimer
 * @see TriggerDefinition
 */
public interface TriggerEvaluator {

    /**
     * Evaluates a single event against the trigger index and returns matching
     * automation IDs.
     *
     * @param event the event to evaluate, never {@code null}
     * @return the list of automation IDs whose triggers matched, never {@code null}
     */
    List<AutomationId> evaluate(EventEnvelope event);

    /**
     * Cancels an active duration timer (AMD-25).
     *
     * <p>Cancellation is performed via {@link Thread#interrupt()} on the timer's
     * virtual thread. No-op if no timer is active for the given key.</p>
     *
     * @param automationId the automation owning the timer, never {@code null}
     * @param triggerIndex the trigger index within the automation's trigger list
     */
    void cancelDurationTimer(AutomationId automationId, int triggerIndex);

    /**
     * Returns the number of currently active duration timers (AMD-25).
     *
     * @return the active timer count, always {@code >= 0}
     */
    int activeDurationTimerCount();
}
