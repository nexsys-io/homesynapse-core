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
 * <p>Five subtypes support {@code for_duration} (AMD-25): {@link StateChangeTrigger},
 * {@link StateTrigger}, {@link NumericThresholdTrigger}, {@link AvailabilityTrigger},
 * and {@link ReachabilityTrigger}. When {@code forDuration} is specified, the trigger
 * does not fire immediately — instead, a {@link DurationTimer} is started. The trigger
 * fires only if the condition remains continuously true for the specified duration.
 * Minimum duration is {@code PT1S}; maximum is governed by
 * {@code automation.trigger.max_for_duration_ms} config. Duration values use ISO 8601
 * {@code PT} format only (no {@code P1D}).</p>
 *
 * <p>Every Tier-1 permit carries a stable {@code triggerId} (AMD-88 §2.5) — assigned
 * in YAML or at load time as a ULID — used by user-facing trace and event surfaces in
 * place of the raw trigger index.</p>
 *
 * <p>This sealed hierarchy permits nine Tier 1 subtypes and three Tier 2 reserved
 * subtypes (twelve total — expanded by AMD-88):</p>
 * <ul>
 *   <li>{@link StateChangeTrigger} — edge-triggered on state transitions</li>
 *   <li>{@link StateTrigger} — level-triggered on state predicate</li>
 *   <li>{@link EventTrigger} — fires on specific event type (no {@code for_duration})</li>
 *   <li>{@link AvailabilityTrigger} — fires on entity-subject availability changes</li>
 *   <li>{@link NumericThresholdTrigger} — fires on numeric threshold crossing</li>
 *   <li>{@link CalendarTrigger} — fires on a calendar-event start/end (AMD-88)</li>
 *   <li>{@link ReachabilityTrigger} — fires on device-subject availability changes (AMD-88)</li>
 *   <li>{@link ManualTrigger} — fires on explicit invocation (AMD-88)</li>
 *   <li>{@link WebhookTrigger} — fires on an inbound webhook (AMD-88 promotion)</li>
 *   <li>{@link TimeTrigger} — Tier 2 reserved</li>
 *   <li>{@link SunTrigger} — Tier 2 reserved</li>
 *   <li>{@link PresenceTrigger} — Tier 2 reserved</li>
 * </ul>
 *
 * <p>All implementations are immutable records. Thread-safe.</p>
 *
 * <p>Defined in Doc 07 §3.4, §8.2; expanded to twelve permits by AMD-88.</p>
 *
 * @see TriggerEvaluator
 * @see DurationTimer
 * @see AutomationDefinition
 */
public sealed interface TriggerDefinition
        permits StateChangeTrigger, StateTrigger, EventTrigger,
                AvailabilityTrigger, NumericThresholdTrigger,
                CalendarTrigger, ReachabilityTrigger, ManualTrigger,
                WebhookTrigger, TimeTrigger, SunTrigger, PresenceTrigger {
}
