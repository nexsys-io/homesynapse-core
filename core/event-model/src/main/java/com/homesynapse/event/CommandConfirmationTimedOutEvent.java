/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import java.util.Objects;

/**
 * Event published when command confirmation times out.
 *
 * This is a DIAGNOSTIC priority event produced by the Pending Command Ledger when the
 * expected state_reported did not arrive within the confirmation timeout. This is not
 * an error condition — it provides evidence for diagnosis and troubleshooting.
 *
 * See Reference Doc 01 §4.3.
 *
 * @param commandEventId event ID of the command_issued event. Non-null.
 * @param resultEventId event ID of the command_result event if one was received;
 *                      {@code null} if no result arrived before timeout.
 */
@EventType(EventTypes.COMMAND_CONFIRMATION_TIMED_OUT)
public record CommandConfirmationTimedOutEvent(
        EventId commandEventId,
        EventId resultEventId
) implements DomainEvent {

    /**
     * Compact constructor with validation.
     *
     * @throws NullPointerException if commandEventId is null
     */
    public CommandConfirmationTimedOutEvent {
        Objects.requireNonNull(commandEventId, "commandEventId must not be null");
    }
}
