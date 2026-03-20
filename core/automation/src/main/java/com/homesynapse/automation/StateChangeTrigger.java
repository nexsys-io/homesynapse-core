/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.time.Duration;
import java.util.Objects;

/**
 * Edge-triggered: fires on {@code state_changed} transitions matching the specified
 * attribute, optionally filtered by {@code from} and/or {@code to} values.
 *
 * <p>At least one of {@code from} or {@code to} must be non-null. This constraint is
 * validated at YAML load time (Phase 3), not in the compact constructor.</p>
 *
 * <p>Supports {@code for_duration} (AMD-25): when specified, the trigger does not fire
 * immediately. A {@link DurationTimer} is started, and the trigger fires only if the
 * target state remains continuously true for the specified duration. Minimum
 * {@code PT1S}, maximum per {@code automation.trigger.max_for_duration_ms} config.
 * ISO 8601 {@code PT} format only (no {@code P1D}).</p>
 *
 * <p>Defined in Doc 07 §3.4, §8.2.</p>
 *
 * @param selector    the entity selector for this trigger, never {@code null}
 * @param attribute   the attribute name to watch for transitions, never {@code null}
 * @param from        the previous attribute value to match; {@code null} means any previous value
 * @param to          the new attribute value to match; {@code null} means any new value
 * @param forDuration the duration the state must be sustained before firing (AMD-25);
 *                    {@code null} means fire immediately on transition
 * @see TriggerDefinition
 * @see TriggerEvaluator
 * @see DurationTimer
 */
public record StateChangeTrigger(
        Selector selector,
        String attribute,
        String from,
        String to,
        Duration forDuration
) implements TriggerDefinition {

    /**
     * Validates non-null fields.
     *
     * @throws NullPointerException if {@code selector} or {@code attribute} is {@code null}
     */
    public StateChangeTrigger {
        Objects.requireNonNull(selector, "selector must not be null");
        Objects.requireNonNull(attribute, "attribute must not be null");
    }
}
