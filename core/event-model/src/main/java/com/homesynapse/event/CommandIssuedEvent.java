/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import com.homesynapse.platform.identity.Ulid;
import java.util.Objects;

/**
 * Event published when a command is issued to a target entity.
 *
 * This is the first event in the command lifecycle. The command contains
 * the capability-defined command name and its parameters as a JSON object string.
 *
 * See Reference Doc 01 §4.6 command_issued payload.
 *
 * @param targetEntityRef ULID of the entity receiving the command. Non-null.
 *                        Always equals the envelope's subjectRef id.
 * @param commandType capability-defined command name (e.g., "set_level", "toggle", "lock").
 *                    Non-null, not blank.
 * @param parameters JSON object string of command parameters as defined by the capability's
 *                   CommandDefinition. Non-null (use "{}" for parameterless commands).
 * @param confirmationTimeoutMs maximum time in milliseconds for Pending Command Ledger to wait
 *                              for state_confirmed. Must be greater than 0.
 * @param idempotencyClass one of IDEMPOTENT, NOT_IDEMPOTENT, or CONDITIONAL.
 *                         Non-null. Sourced from capability's CommandDefinition.
 */
public record CommandIssuedEvent(
        Ulid targetEntityRef,
        String commandType,
        String parameters,
        int confirmationTimeoutMs,
        CommandIdempotency idempotencyClass
) implements DomainEvent {

    /**
     * Compact constructor with validation.
     *
     * @throws NullPointerException if targetEntityRef, commandType, parameters,
     *                              or idempotencyClass is null
     * @throws IllegalArgumentException if commandType is blank, parameters is blank,
     *                                   or confirmationTimeoutMs is not greater than 0
     */
    public CommandIssuedEvent {
        Objects.requireNonNull(targetEntityRef, "targetEntityRef must not be null");
        Objects.requireNonNull(commandType, "commandType must not be null");
        Objects.requireNonNull(parameters, "parameters must not be null");
        Objects.requireNonNull(idempotencyClass, "idempotencyClass must not be null");

        if (commandType.isBlank()) {
            throw new IllegalArgumentException("commandType must not be blank");
        }
        if (parameters.isBlank()) {
            throw new IllegalArgumentException("parameters must not be blank");
        }
        if (confirmationTimeoutMs <= 0) {
            throw new IllegalArgumentException("confirmationTimeoutMs must be greater than 0");
        }
    }
}
