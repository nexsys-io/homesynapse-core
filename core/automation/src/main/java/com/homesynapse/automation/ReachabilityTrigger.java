/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.time.Duration;
import java.util.Objects;

import com.homesynapse.platform.identity.DeviceId;
import com.homesynapse.state.Availability;

/**
 * Tier 1 trigger that fires on <strong>device-subject</strong>
 * {@code availability_changed} transitions (AMD-88 §2.2).
 *
 * <p>Unlike {@link AvailabilityTrigger} (which watches entity-subject availability),
 * this trigger keys on a {@link DeviceId} and matches {@code availability_changed}
 * events whose subject is a device. {@code targetAvailability} reuses
 * {@link Availability} exactly as {@code AvailabilityTrigger} does; the
 * event-driven model means the consumed {@code availability_changed} event IS the
 * transition.</p>
 *
 * <p>Supports {@code for_duration} (AMD-25): the {@code debounce} concept from the
 * source research is expressed through the standard duration-timer machinery —
 * when non-null, the trigger fires only if the device remains at
 * {@code targetAvailability} for the sustained window.</p>
 *
 * <p><strong>Granularity note (R-δ AX-8):</strong> {@code Availability} models only
 * {@code AVAILABLE}/{@code UNAVAILABLE}/{@code UNKNOWN} at this baseline. It does not
 * distinguish a battery device legitimately asleep from a mains device that is dead;
 * evaluation cannot make that distinction until the availability substrate gains the
 * granularity. This is an integration-level gap, not collapsed here.</p>
 *
 * <p>Defined in AMD-88 §2.2; Doc 07 §3.4, §8.2.</p>
 *
 * @param deviceId           the device whose availability is watched, never {@code null}
 * @param targetAvailability the availability state to match, never {@code null}
 * @param forDuration        the duration the availability must be sustained before
 *                           firing (AMD-25); {@code null} means fire immediately
 * @param triggerId          the stable, user-facing trigger identity (AMD-88 §2.5),
 *                           never {@code null}
 * @see TriggerDefinition
 * @see AvailabilityTrigger
 * @see DurationTimer
 */
public record ReachabilityTrigger(
        DeviceId deviceId,
        Availability targetAvailability,
        Duration forDuration,
        String triggerId
) implements TriggerDefinition {

    /**
     * Validates non-null fields. The {@code forDuration} is intentionally nullable.
     *
     * @throws NullPointerException if {@code deviceId}, {@code targetAvailability},
     *                              or {@code triggerId} is {@code null}
     */
    public ReachabilityTrigger {
        Objects.requireNonNull(deviceId, "deviceId must not be null");
        Objects.requireNonNull(targetAvailability, "targetAvailability must not be null");
        Objects.requireNonNull(triggerId, "triggerId must not be null");
    }
}
