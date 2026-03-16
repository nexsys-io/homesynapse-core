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
 * Protocol-level outcome from an integration adapter. NORMAL priority is used for
 * successful acknowledgments, while CRITICAL priority is used for rejections and timeouts.
 *
 * See Reference Doc 01 §4.3.
 *
 * @param targetEntityRef ULID of the entity that the command targeted. Non-null.
 * @param commandType the name of the command. Non-null, not blank.
 * @param outcome one of "acknowledged", "rejected", or "timed_out". Non-null, not blank.
 * @param failureReason human-readable reason for rejection or timeout. May be {@code null}
 *                      when outcome is "acknowledged".
 */
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
