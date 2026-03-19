/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration;

import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.IntegrationId;
import com.homesynapse.platform.identity.Ulid;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Carries a dispatched command targeting a specific entity, delivered to the
 * adapter's {@link CommandHandler} by the supervisor (Doc 05 §8.2, §8.6).
 *
 * <p>The command flow: a {@code command_dispatched} domain event is published
 * (typically by the REST API or Automation Engine). The supervisor subscribes
 * to these events, filters by {@link #integrationId()}, extracts the command
 * data into a {@code CommandEnvelope}, and invokes the adapter's
 * {@link CommandHandler#handle(CommandEnvelope)} method on the adapter's thread.
 * The adapter translates the command to protocol-specific operations (e.g., a
 * ZCL frame for Zigbee, an HTTP request for Hue) and publishes a
 * {@code command_result} event via
 * {@link com.homesynapse.event.EventPublisher}.</p>
 *
 * <p>The {@link #commandEventId()} and {@link #correlationId()} fields enable
 * causal context propagation: the adapter uses them to construct a
 * {@link com.homesynapse.event.CausalContext} for the resulting
 * {@code command_result} event, linking it back to the original command in
 * the causal chain.</p>
 *
 * <p>The {@link #parameters()} map is defensively copied to an unmodifiable map
 * at construction time. This record is immutable and thread-safe.</p>
 *
 * @param entityRef       the target entity for this command; never {@code null}
 * @param commandName     the capability-defined command name (e.g.,
 *                        {@code "set_on_off"}, {@code "set_brightness"});
 *                        never {@code null} or blank
 * @param parameters      command parameters as key-value pairs, defined by the
 *                        capability's command schema; never {@code null},
 *                        may be empty; returned as an unmodifiable map
 * @param commandEventId  the event ID of the originating
 *                        {@code command_dispatched} event, used as the
 *                        causation ID in the resulting {@code command_result}
 *                        event; never {@code null}
 * @param correlationId   the correlation ID from the originating command's
 *                        causal context, propagated to the {@code command_result}
 *                        event; never {@code null}
 * @param integrationId   the integration instance that should handle this
 *                        command; never {@code null}
 *
 * @see CommandHandler
 * @see com.homesynapse.event.EventPublisher
 * @see com.homesynapse.event.CausalContext
 */
public record CommandEnvelope(
        EntityId entityRef,
        String commandName,
        Map<String, Object> parameters,
        Ulid commandEventId,
        Ulid correlationId,
        IntegrationId integrationId
) {

    /**
     * Validates all fields and defensively copies the parameters map to an
     * unmodifiable map.
     */
    public CommandEnvelope {
        Objects.requireNonNull(entityRef, "entityRef must not be null");
        Objects.requireNonNull(commandName, "commandName must not be null");
        if (commandName.isBlank()) {
            throw new IllegalArgumentException("commandName must not be blank");
        }
        Objects.requireNonNull(parameters, "parameters must not be null");
        Objects.requireNonNull(commandEventId, "commandEventId must not be null");
        Objects.requireNonNull(correlationId, "correlationId must not be null");
        Objects.requireNonNull(integrationId, "integrationId must not be null");

        // Defensive copy to unmodifiable map
        parameters = Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
    }
}
