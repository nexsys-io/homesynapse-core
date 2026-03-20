/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

/**
 * Trigger definitions parsed from {@code automations.yaml}.
 *
 * <p>Each subtype matches a specific event pattern. When an incoming event matches
 * a trigger's criteria, the automation's conditions are evaluated and, if satisfied,
 * its action sequence executes. Triggers are evaluated by the {@link TriggerEvaluator},
 * which maintains a trigger index for O(1) event-type-to-automation lookup.</p>
 *
 * <p>Four subtypes support {@code for_duration} (AMD-25): {@link StateChangeTrigger},
 * {@link StateTrigger}, {@link NumericThresholdTrigger}, and {@link AvailabilityTrigger}.
 * When {@code forDuration} is specified, the trigger does not fire immediately — instead,
 * a {@link DurationTimer} is started. The trigger fires only if the condition remains
 * continuously true for the specified duration. Minimum duration is {@code PT1S};
 * maximum is governed by {@code automation.trigger.max_for_duration_ms} config.
 * Duration values use ISO 8601 {@code PT} format only (no {@code P1D}).</p>
 *
 * <p>This sealed hierarchy permits five Tier 1 subtypes and four Tier 2 reserved
 * subtypes:</p>
 * <ul>
 *   <li>{@link StateChangeTrigger} — edge-triggered on state transitions</li>
 *   <li>{@link StateTrigger} — level-triggered on state predicate</li>
 *   <li>{@link EventTrigger} — fires on specific event type (no {@code for_duration})</li>
 *   <li>{@link AvailabilityTrigger} — fires on availability changes</li>
 *   <li>{@link NumericThresholdTrigger} — fires on numeric threshold crossing</li>
 *   <li>{@link TimeTrigger} — Tier 2 reserved</li>
 *   <li>{@link SunTrigger} — Tier 2 reserved</li>
 *   <li>{@link PresenceTrigger} — Tier 2 reserved</li>
 *   <li>{@link WebhookTrigger} — Tier 2 reserved</li>
 * </ul>
 *
 * <p>All implementations are immutable records. Thread-safe.</p>
 *
 * <p>Defined in Doc 07 §3.4, §8.2.</p>
 *
 * @see TriggerEvaluator
 * @see DurationTimer
 * @see AutomationDefinition
 */
public sealed interface TriggerDefinition
        permits StateChangeTrigger, StateTrigger, EventTrigger,
                AvailabilityTrigger, NumericThresholdTrigger,
                TimeTrigger, SunTrigger, PresenceTrigger, WebhookTrigger {
}
