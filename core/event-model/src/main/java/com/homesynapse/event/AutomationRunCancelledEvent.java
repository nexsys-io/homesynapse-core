/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import java.util.Objects;

import com.homesynapse.platform.identity.AutomationId;
import com.homesynapse.platform.identity.Ulid;

/**
 * Payload for {@code automation_run_cancelled} events — a {@code RESTART}-mode trigger
 * cancelled an in-flight Run before starting its replacement (Doc 07 §3.6; AMD-92 row 8).
 *
 * <p>Emitted on the victim Run when a new trigger fires for a {@code RESTART}-mode
 * automation while a Run is active. The active Run is interrupted (transitioning to
 * {@code ABORTED}) and a fresh Run begins for the replacing event. This diagnostic
 * records the causal pairing — which Run was cancelled, and which event replaced it.</p>
 *
 * <p><strong>Type residency (AMD-92-INV-01).</strong> {@code cancelledRunId} is a bare
 * {@link Ulid} (the automation-resident {@code RunId} is flattened). No
 * automation-resident type appears here.</p>
 *
 * <p>Default priority: {@link EventPriority#DIAGNOSTIC DIAGNOSTIC}.</p>
 *
 * @param automationId      the automation whose Run was cancelled; never {@code null}
 * @param cancelledRunId    the cancelled Run (bare ULID — flattened {@code RunId});
 *                          never {@code null}
 * @param replacingEventId  the event that triggered the replacing Run; never {@code null}
 * @param triggeringEventId the event that originally triggered the cancelled Run;
 *                          never {@code null}
 * @see DomainEvent
 * @see EventTypes#AUTOMATION_RUN_CANCELLED
 */
@EventType(EventTypes.AUTOMATION_RUN_CANCELLED)
public record AutomationRunCancelledEvent(
        AutomationId automationId,
        Ulid cancelledRunId,
        EventId replacingEventId,
        EventId triggeringEventId
) implements DomainEvent {

    /**
     * Validates that all fields are non-null.
     *
     * @throws NullPointerException if any field is {@code null}
     */
    public AutomationRunCancelledEvent {
        Objects.requireNonNull(automationId, "automationId must not be null");
        Objects.requireNonNull(cancelledRunId, "cancelledRunId must not be null");
        Objects.requireNonNull(replacingEventId, "replacingEventId must not be null");
        Objects.requireNonNull(triggeringEventId, "triggeringEventId must not be null");
    }
}
