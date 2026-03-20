/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.time.Duration;
import java.util.Objects;

/**
 * Level-triggered: fires on every {@code state_changed} event where the specified
 * attribute equals the specified value.
 *
 * <p>Unlike {@link StateChangeTrigger} (which detects transitions), this trigger
 * matches the current state predicate on every relevant event.</p>
 *
 * <p>Supports {@code for_duration} (AMD-25): when specified, the trigger does not fire
 * immediately. A {@link DurationTimer} is started, and the trigger fires only if the
 * predicate remains continuously true for the specified duration. Minimum
 * {@code PT1S}, maximum per {@code automation.trigger.max_for_duration_ms} config.
 * ISO 8601 {@code PT} format only (no {@code P1D}).</p>
 *
 * <p>Defined in Doc 07 §3.4, §8.2.</p>
 *
 * @param selector    the entity selector for this trigger, never {@code null}
 * @param attribute   the attribute name to watch, never {@code null}
 * @param value       the attribute value that must match, never {@code null}
 * @param forDuration the duration the state must be sustained before firing (AMD-25);
 *                    {@code null} means fire immediately on match
 * @see TriggerDefinition
 * @see TriggerEvaluator
 * @see DurationTimer
 */
public record StateTrigger(
        Selector selector,
        String attribute,
        String value,
        Duration forDuration
) implements TriggerDefinition {

    /**
     * Validates non-null fields.
     *
     * @throws NullPointerException if {@code selector}, {@code attribute}, or
     *                              {@code value} is {@code null}
     */
    public StateTrigger {
        Objects.requireNonNull(selector, "selector must not be null");
        Objects.requireNonNull(attribute, "attribute must not be null");
        Objects.requireNonNull(value, "value must not be null");
    }
}
