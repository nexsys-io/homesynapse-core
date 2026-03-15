/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

package com.homesynapse.event;

import java.util.Objects;

/**
 * Payload for {@code automation_completed} events — a run finished with a terminal status
 * (Doc 01 §4.3).
 *
 * <p>Produced by the Automation Engine when an automation run reaches a terminal state.
 * The {@code status} field carries one of {@code "success"}, {@code "failure"}, or
 * {@code "aborted"}. On failure, {@code failureReason} provides a human-readable
 * description of what went wrong; on success or abort, it is {@code null}.</p>
 *
 * <p>Default priority: {@link EventPriority#NORMAL NORMAL}.</p>
 *
 * @param status        the terminal status: {@code "success"}, {@code "failure"}, or
 *                      {@code "aborted"}; never {@code null} or blank
 * @param failureReason a human-readable failure description; {@code null} when
 *                      {@code status} is {@code "success"} or {@code "aborted"}
 * @param durationMs    the wall-clock duration of the automation run in milliseconds;
 *                      must be {@code >= 0}
 * @see AutomationTriggeredEvent
 * @see DomainEvent
 * @see EventTypes#AUTOMATION_COMPLETED
 */
public record AutomationCompletedEvent(
        String status,
        String failureReason,
        long durationMs
) implements DomainEvent {

    /**
     * Validates that {@code status} is non-null and non-blank, and that
     * {@code durationMs} is non-negative.
     *
     * @throws NullPointerException     if {@code status} is {@code null}
     * @throws IllegalArgumentException if {@code status} is blank or
     *                                  {@code durationMs} is negative
     */
    public AutomationCompletedEvent {
        Objects.requireNonNull(status, "status cannot be null");
        if (status.isBlank()) {
            throw new IllegalArgumentException("status cannot be blank");
        }
        if (durationMs < 0) {
            throw new IllegalArgumentException("durationMs cannot be negative");
        }
    }
}
