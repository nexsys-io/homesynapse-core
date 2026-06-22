/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import java.util.Objects;

import com.homesynapse.platform.identity.Ulid;

/**
 * Payload for {@code automation_action_completed} (AMD-92 row 6) — emitted immediately after
 * an action step finishes within a Run's virtual thread (Doc 07 §3.9).
 *
 * <p>The {@code outcome} is one of {@code "success"} (the action executed), {@code "skipped"}
 * (the action was not applicable — e.g. a branch not taken, or an unavailable target under
 * {@link EventPriority#DIAGNOSTIC SKIP} policy), or {@code "error"} (the action failed; the
 * Run then terminates fail-fast per Doc 07 §6.2). {@code errorDetail} is populated only for
 * {@code "error"}.</p>
 *
 * <p><strong>Type residency (AMD-92-INV-01).</strong> {@code runId} is a bare {@link Ulid}
 * (the automation-resident {@code RunId} is flattened). No automation-resident type appears
 * here.</p>
 *
 * <p>Default priority: {@link EventPriority#DIAGNOSTIC DIAGNOSTIC}.</p>
 *
 * @param runId       the Run instance identifier (bare ULID — flattened {@code RunId});
 *                    never {@code null}
 * @param actionIndex the 0-based index of this action in the automation's action list;
 *                    {@code >= 0}
 * @param outcome     {@code "success"}, {@code "skipped"}, or {@code "error"}; never
 *                    {@code null} or blank
 * @param errorDetail a human-readable failure description; {@code null} unless
 *                    {@code outcome} is {@code "error"}
 * @see DomainEvent
 * @see EventTypes#AUTOMATION_ACTION_COMPLETED
 */
@EventType(EventTypes.AUTOMATION_ACTION_COMPLETED)
public record AutomationActionCompletedEvent(
        Ulid runId,
        int actionIndex,
        String outcome,
        String errorDetail
) implements DomainEvent {

    /**
     * Validates required fields and a non-negative index. The {@code errorDetail} field is
     * nullable.
     *
     * @throws NullPointerException     if {@code runId} or {@code outcome} is {@code null}
     * @throws IllegalArgumentException if {@code outcome} is blank or {@code actionIndex}
     *                                  is negative
     */
    public AutomationActionCompletedEvent {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
        if (outcome.isBlank()) {
            throw new IllegalArgumentException("outcome must not be blank");
        }
        if (actionIndex < 0) {
            throw new IllegalArgumentException("actionIndex must be >= 0: " + actionIndex);
        }
    }
}
