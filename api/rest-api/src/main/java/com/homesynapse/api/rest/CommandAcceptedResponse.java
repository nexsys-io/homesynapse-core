/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import java.time.Instant;
import java.util.Objects;

/**
 * The {@code 202 Accepted} response for successful command issuance.
 *
 * <p>"Accepted" means the command is validated and durably persisted as a
 * {@code command_issued} event — it does NOT mean the device has received or
 * executed the command (Doc 09 §4.4). This is the most important behavioral
 * contract at the command API boundary: acceptance guarantees durability
 * (INV-ES-04), not execution.</p>
 *
 * <p>Clients track the full command lifecycle via
 * {@code GET /api/v1/commands/{command_id}}, which returns a
 * {@link CommandStatusResponse} with the current phase and phase details.</p>
 *
 * <p>Thread-safe (immutable record).</p>
 *
 * @param commandId     the {@code command_issued} event's {@code event_id}, used for
 *                      lifecycle tracking, never {@code null}
 * @param correlationId the event's {@code correlation_id}, set by {@code publishRoot()}
 *                      to the event's own ID (Doc 01 §8.3), never {@code null}
 * @param entityId      the target entity identifier, never {@code null}
 * @param status        always {@code "accepted"} for this response type,
 *                      never {@code null}
 * @param acceptedAt    timestamp of the {@code command_issued} event,
 *                      never {@code null}
 * @param viewPosition  the event store position at which the command was persisted
 *
 * @see CommandRequest
 * @see CommandStatusResponse
 * @see <a href="Doc 09 §4.4">Command Accepted Response</a>
 */
public record CommandAcceptedResponse(
        String commandId,
        String correlationId,
        String entityId,
        String status,
        Instant acceptedAt,
        long viewPosition) {

    /**
     * Creates a new command accepted response with validation of required fields.
     *
     * @throws NullPointerException if any {@code String} or {@code Instant}
     *                              parameter is {@code null}
     */
    public CommandAcceptedResponse {
        Objects.requireNonNull(commandId, "commandId must not be null");
        Objects.requireNonNull(correlationId, "correlationId must not be null");
        Objects.requireNonNull(entityId, "entityId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(acceptedAt, "acceptedAt must not be null");
    }
}
