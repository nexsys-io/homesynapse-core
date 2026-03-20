/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import com.homesynapse.event.EventId;
import com.homesynapse.platform.identity.AutomationId;
import com.homesynapse.platform.identity.EntityId;

/**
 * Tracks an active {@code for_duration} timer (AMD-25).
 *
 * <h2>Lifecycle</h2>
 *
 * <p>A duration timer is started when a trigger's {@code for_duration} field is non-null
 * and the trigger's condition first becomes true. The timer sleeps on a virtual thread
 * for the specified duration. If the condition remains continuously true until expiry,
 * the trigger fires using the {@link #startingEventId()} as the triggering event for
 * deduplication purposes. Timer cancellation is via {@link Thread#interrupt()} on the
 * {@link #virtualThread()}.</p>
 *
 * <h2>Persistence</h2>
 *
 * <p>Duration timers are NOT persisted — they are rebuilt from events on
 * REPLAY&rarr;LIVE transition. This is consistent with the virtual thread lifecycle:
 * threads are not serializable.</p>
 *
 * <h2>Keying</h2>
 *
 * <p>Each timer is uniquely keyed by {@code (automationId, triggerIndex)}. At most
 * one timer may be active per key.</p>
 *
 * <p>Defined in Doc 07 §8.2, AMD-25.</p>
 *
 * @param automationId   the automation this timer belongs to, never {@code null}
 * @param triggerIndex   the index of the trigger within the automation's trigger list
 * @param startingEventId the event that initiated this timer, never {@code null}
 * @param entityRef      the entity being monitored, never {@code null}
 * @param forDuration    the required sustained duration, never {@code null}
 * @param startedAt      when the timer was started, never {@code null}
 * @param expiresAt      when the timer will expire, never {@code null}
 * @param virtualThread  the sleeping virtual thread, never {@code null}
 * @see TriggerEvaluator
 * @see TriggerDefinition
 */
public record DurationTimer(
        AutomationId automationId,
        int triggerIndex,
        EventId startingEventId,
        EntityId entityRef,
        Duration forDuration,
        Instant startedAt,
        Instant expiresAt,
        Thread virtualThread
) {

    /**
     * Validates that all object fields are non-null.
     *
     * @throws NullPointerException if any non-primitive field is {@code null}
     */
    public DurationTimer {
        Objects.requireNonNull(automationId, "automationId must not be null");
        Objects.requireNonNull(startingEventId, "startingEventId must not be null");
        Objects.requireNonNull(entityRef, "entityRef must not be null");
        Objects.requireNonNull(forDuration, "forDuration must not be null");
        Objects.requireNonNull(startedAt, "startedAt must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        Objects.requireNonNull(virtualThread, "virtualThread must not be null");
    }
}
