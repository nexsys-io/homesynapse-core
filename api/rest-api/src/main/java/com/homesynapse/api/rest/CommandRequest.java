/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import java.util.Map;
import java.util.Objects;

/**
 * Typed command input received by {@code POST /api/v1/entities/{entity_id}/commands}.
 *
 * <p>All three fields are required (Doc 09 §4.3, §8.2). The {@link #capability()}
 * and {@link #command()} are validated against the target entity's declared
 * capabilities via {@code CommandValidator} (Doc 02 §8.1). The {@link #parameters()}
 * map is validated against the command's parameter schema from
 * {@code CommandDefinition}.</p>
 *
 * <p>Thread-safe (immutable record). The parameters map is unmodifiable.</p>
 *
 * @param capability the capability identifier (e.g., {@code "level_control"}),
 *                   never {@code null}
 * @param command    the command name (e.g., {@code "set_level"}),
 *                   never {@code null}
 * @param parameters command parameters (unmodifiable), never {@code null}
 *
 * @see CommandAcceptedResponse
 * @see CommandStatusResponse
 * @see <a href="Doc 09 §4.3">Command Issuance</a>
 */
public record CommandRequest(String capability, String command, Map<String, Object> parameters) {

    /**
     * Creates a new command request with validation of required fields.
     *
     * <p>The {@code parameters} map is made unmodifiable via {@link Map#copyOf(Map)}.</p>
     *
     * @throws NullPointerException if any parameter is {@code null}
     */
    public CommandRequest {
        Objects.requireNonNull(capability, "capability must not be null");
        Objects.requireNonNull(command, "command must not be null");
        Objects.requireNonNull(parameters, "parameters must not be null");
        parameters = Map.copyOf(parameters);
    }
}
