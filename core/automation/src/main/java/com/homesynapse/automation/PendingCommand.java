/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.time.Instant;
import java.util.Objects;

import com.homesynapse.device.Expectation;
import com.homesynapse.event.CommandIdempotency;
import com.homesynapse.event.EventId;
import com.homesynapse.platform.identity.EntityId;

/**
 * In-flight command tracking entry in the {@link PendingCommandLedger}.
 *
 * <h2>Command Lifecycle</h2>
 *
 * <p>A pending command progresses through the following states (Doc 07 §3.11.2):</p>
 * <ol>
 *   <li>{@link PendingStatus#DISPATCHED} — after {@code command_issued} event is processed</li>
 *   <li>{@link PendingStatus#ACKNOWLEDGED} — after the integration adapter acknowledges receipt</li>
 *   <li>{@link PendingStatus#CONFIRMED} — after the {@link #expectation()} matches an incoming
 *       {@code state_reported} event. The ledger produces a {@code state_confirmed} event.</li>
 *   <li>{@link PendingStatus#TIMED_OUT} — after the {@link #deadline()} expires without
 *       confirmation. The ledger produces a {@code command_confirmation_timed_out} event.</li>
 *   <li>{@link PendingStatus#EXPIRED} — for {@link CommandIdempotency#NOT_IDEMPOTENT}
 *       commands discovered pending after system restart.</li>
 * </ol>
 *
 * <p>The {@link #expectation()} is evaluated against incoming {@code state_reported}
 * events using {@link Expectation#evaluate(com.homesynapse.device.AttributeValue)},
 * which returns a {@link com.homesynapse.device.ConfirmationResult}.</p>
 *
 * <p>Defined in Doc 07 §4.3, §8.2.</p>
 *
 * @param commandEventId the event ID of the {@code command_issued} event, never {@code null}
 * @param targetRef      the entity the command targets, never {@code null}
 * @param commandName    the command name (e.g., {@code "set_level"}), never {@code null}
 * @param targetAttribute the attribute expected to change, never {@code null}
 * @param expectation    the confirmation expectation to evaluate against state reports,
 *                       never {@code null}
 * @param deadline       the confirmation deadline, never {@code null}
 * @param idempotency    the command's idempotency classification, never {@code null}
 * @param status         the current lifecycle state, never {@code null}
 * @see PendingCommandLedger
 * @see PendingStatus
 * @see Expectation
 * @see CommandDispatchService
 */
public record PendingCommand(
        EventId commandEventId,
        EntityId targetRef,
        String commandName,
        String targetAttribute,
        Expectation expectation,
        Instant deadline,
        CommandIdempotency idempotency,
        PendingStatus status
) {

    /**
     * Validates that all fields are non-null.
     *
     * @throws NullPointerException if any field is {@code null}
     */
    public PendingCommand {
        Objects.requireNonNull(commandEventId, "commandEventId must not be null");
        Objects.requireNonNull(targetRef, "targetRef must not be null");
        Objects.requireNonNull(commandName, "commandName must not be null");
        Objects.requireNonNull(targetAttribute, "targetAttribute must not be null");
        Objects.requireNonNull(expectation, "expectation must not be null");
        Objects.requireNonNull(deadline, "deadline must not be null");
        Objects.requireNonNull(idempotency, "idempotency must not be null");
        Objects.requireNonNull(status, "status must not be null");
    }
}
