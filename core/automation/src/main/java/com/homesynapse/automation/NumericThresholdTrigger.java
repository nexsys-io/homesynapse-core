/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.time.Duration;
import java.util.Objects;

/**
 * Fires when a numeric attribute crosses a specified threshold.
 *
 * <p>At least one of {@code above} or {@code below} must be non-null. This constraint
 * is validated at YAML load time (Phase 3), not in the compact constructor.</p>
 *
 * <p>Supports {@code for_duration} (AMD-25): when specified, the trigger does not fire
 * immediately. A {@link DurationTimer} is started, and the trigger fires only if the
 * threshold condition remains continuously true for the specified duration. Minimum
 * {@code PT1S}, maximum per {@code automation.trigger.max_for_duration_ms} config.
 * ISO 8601 {@code PT} format only (no {@code P1D}).</p>
 *
 * <p>Defined in Doc 07 §3.4, §8.2.</p>
 *
 * @param selector    the entity selector for this trigger, never {@code null}
 * @param attribute   the numeric attribute name to watch, never {@code null}
 * @param above       the upper threshold; {@code null} if only lower bound is checked
 * @param below       the lower threshold; {@code null} if only upper bound is checked
 * @param forDuration the duration the threshold must be sustained before firing (AMD-25);
 *                    {@code null} means fire immediately on crossing
 * @see TriggerDefinition
 * @see TriggerEvaluator
 * @see DurationTimer
 */
public record NumericThresholdTrigger(
        Selector selector,
        String attribute,
        Double above,
        Double below,
        Duration forDuration
) implements TriggerDefinition {

    /**
     * Validates non-null fields.
     *
     * @throws NullPointerException if {@code selector} or {@code attribute} is {@code null}
     */
    public NumericThresholdTrigger {
        Objects.requireNonNull(selector, "selector must not be null");
        Objects.requireNonNull(attribute, "attribute must not be null");
    }
}
