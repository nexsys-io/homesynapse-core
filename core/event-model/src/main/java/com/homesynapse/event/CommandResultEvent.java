/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import com.homesynapse.platform.identity.Ulid;
import java.util.Objects;

/**
 * Event published when a command outcome is reported by the protocol layer.
 *
 * Protocol-level outcome from an integration adapter, the command router, the
 * dispatch service, or the pending command ledger. NORMAL priority is used for
 * successful acknowledgments, while CRITICAL priority is used for rejections
 * and timeouts.
 *
 * See Reference Doc 01 §4.3; Doc 07 §3.11.2.
 *
 * @param targetEntityRef ULID of the entity that the command targeted. Non-null.
 * @param commandType the name of the command. Non-null, not blank.
 * @param outcome the live vocabulary (M9.4b §4.3 currency): {@code acknowledged} |
 *                {@code rejected} | {@code timed_out} | {@code invalid} |
 *                {@code unsupported} | {@code handler_error} |
 *                {@code integration_unavailable} | {@code superseded} |
 *                {@code expired_on_restart} | {@code unconfirmed}. The last four
 *                (including {@code invalid}) are DISPOSITIONS — terminal reports the
 *                pending command ledger's {@code onCommandResult} guard skips, never
 *                terminal-matches; integration adapters may publish additional
 *                protocol-specific strings. Non-null, not blank.
 * @param failureReason human-readable reason for a non-acknowledged outcome. May be
 *                      {@code null} when outcome is "acknowledged".
 */
@EventType(EventTypes.COMMAND_RESULT)
public record CommandResultEvent(
        Ulid targetEntityRef,
        String commandType,
        String outcome,
        String failureReason
) implements DomainEvent {

    /**
     * Compact constructor with validation.
     *
     * @throws NullPointerException if targetEntityRef, commandType, or outcome is null
     * @throws IllegalArgumentException if commandType or outcome is blank
     */
    public CommandResultEvent {
        Objects.requireNonNull(targetEntityRef, "targetEntityRef must not be null");
        Objects.requireNonNull(commandType, "commandType must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");

        if (commandType.isBlank()) {
            throw new IllegalArgumentException("commandType must not be blank");
        }
        if (outcome.isBlank()) {
            throw new IllegalArgumentException("outcome must not be blank");
        }
    }
}
