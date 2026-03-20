/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.time.Duration;
import java.util.Objects;

import com.homesynapse.state.Availability;

/**
 * Fires on {@code availability_changed} events matching the target availability state.
 *
 * <p>Supports {@code for_duration} (AMD-25): when specified, the trigger does not fire
 * immediately. A {@link DurationTimer} is started, and the trigger fires only if the
 * entity remains at the target availability for the specified duration. Minimum
 * {@code PT1S}, maximum per {@code automation.trigger.max_for_duration_ms} config.
 * ISO 8601 {@code PT} format only (no {@code P1D}).</p>
 *
 * <p>Defined in Doc 07 §3.4, §8.2.</p>
 *
 * @param selector           the entity selector for this trigger, never {@code null}
 * @param targetAvailability the availability state to match, never {@code null}
 * @param forDuration        the duration the availability must be sustained before firing
 *                           (AMD-25); {@code null} means fire immediately on match
 * @see TriggerDefinition
 * @see TriggerEvaluator
 * @see DurationTimer
 */
public record AvailabilityTrigger(
        Selector selector,
        Availability targetAvailability,
        Duration forDuration
) implements TriggerDefinition {

    /**
     * Validates non-null fields.
     *
     * @throws NullPointerException if {@code selector} or {@code targetAvailability}
     *                              is {@code null}
     */
    public AvailabilityTrigger {
        Objects.requireNonNull(selector, "selector must not be null");
        Objects.requireNonNull(targetAvailability, "targetAvailability must not be null");
    }
}
