/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import java.util.Map;
import java.util.Objects;

/**
 * Four-phase command lifecycle status assembled from EventStore queries by correlation ID.
 *
 * <p>This response represents HomeSynapse's strongest competitive differentiator at
 * the API surface (Doc 09 §3.4, §4.5). No existing smart home platform API exposes
 * the full {@code accepted → dispatched → acknowledged → confirmed} lifecycle. The
 * event-sourced architecture produces these lifecycle events naturally; this response
 * type makes them accessible via {@code GET /api/v1/commands/{command_id}}.</p>
 *
 * <p>The {@link #lifecycle()} map uses {@link CommandLifecyclePhase} as keys and only
 * contains entries for phases that have completed. An absent key means the phase has
 * not yet occurred — there are no sentinel values. This matches the JSON structure
 * where only completed phases appear as keys in the {@code lifecycle} object.</p>
 *
 * <p>The {@link #terminal()} flag is {@code true} when the lifecycle is complete:
 * the current phase is {@link CommandLifecyclePhase#CONFIRMED},
 * {@link CommandLifecyclePhase#CONFIRMATION_TIMED_OUT}, or a rejected/timed-out
 * {@link CommandLifecyclePhase#ACKNOWLEDGED}.</p>
 *
 * <p>Thread-safe (immutable record). The lifecycle map is unmodifiable.</p>
 *
 * @param commandId     the {@code command_issued} event's {@code event_id},
 *                      never {@code null}
 * @param correlationId the event's {@code correlation_id}, never {@code null}
 * @param entityId      the target entity identifier, never {@code null}
 * @param capability    the capability identifier, never {@code null}
 * @param command       the command name, never {@code null}
 * @param lifecycle     completed phases mapped to their details (unmodifiable),
 *                      never {@code null}
 * @param currentPhase  the latest completed phase, never {@code null}
 * @param terminal      {@code true} when the lifecycle is complete
 *
 * @see CommandLifecyclePhase
 * @see LifecyclePhaseDetail
 * @see CommandAcceptedResponse
 * @see <a href="Doc 09 §3.4">Command Lifecycle</a>
 * @see <a href="Doc 09 §4.5">Command Status Endpoint</a>
 */
public record CommandStatusResponse(
        String commandId,
        String correlationId,
        String entityId,
        String capability,
        String command,
        Map<CommandLifecyclePhase, LifecyclePhaseDetail> lifecycle,
        CommandLifecyclePhase currentPhase,
        boolean terminal) {

    /**
     * Creates a new command status response with validation of required fields.
     *
     * <p>The {@code lifecycle} map is made unmodifiable via {@link Map#copyOf(Map)}.</p>
     *
     * @throws NullPointerException if any non-primitive parameter is {@code null}
     */
    public CommandStatusResponse {
        Objects.requireNonNull(commandId, "commandId must not be null");
        Objects.requireNonNull(correlationId, "correlationId must not be null");
        Objects.requireNonNull(entityId, "entityId must not be null");
        Objects.requireNonNull(capability, "capability must not be null");
        Objects.requireNonNull(command, "command must not be null");
        Objects.requireNonNull(lifecycle, "lifecycle must not be null");
        Objects.requireNonNull(currentPhase, "currentPhase must not be null");
        lifecycle = Map.copyOf(lifecycle);
    }
}
