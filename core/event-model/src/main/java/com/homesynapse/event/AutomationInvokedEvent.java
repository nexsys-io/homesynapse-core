/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

/**
 * Event emitted when an automation is explicitly invoked (AMD-92 row 3).
 *
 * <p>Produced by the REST {@code POST /automations/{id}/invoke} endpoint (M10), a UI
 * button, or voice. The invoking actor is carried on the envelope's {@code actorRef}
 * (not in this payload — the envelope owns attribution, AMD-92 §2.4). A
 * {@link com.homesynapse.event.EventTypes#AUTOMATION_INVOKED} event is what a
 * {@code ManualTrigger} (AMD-88) consumes to initiate a Run.</p>
 *
 * <p>Priority: NORMAL. Subject: Automation. Doc 07 §3.7 (event-table addition,
 * AMD-92 R92-1).</p>
 *
 * @param invocationContext a free-text origin note surfaced in the trace; {@code null}
 *                          when no context was supplied
 * @see DomainEvent
 * @see EventTypes#AUTOMATION_INVOKED
 */
@EventType(EventTypes.AUTOMATION_INVOKED)
public record AutomationInvokedEvent(
        String invocationContext
) implements DomainEvent {
    // invocationContext is intentionally nullable — no compact-constructor guard.
}
