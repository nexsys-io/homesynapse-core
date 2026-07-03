/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.function.Function;

/**
 * Decodes an automation command's {@code command_issued.parameters} JSON object
 * string back to its parameter map using the persistence {@link ObjectMapper}
 * (the configuration {@link PersistenceObjectMapper#create()} builds) — the
 * M7.4b {@link CommandParameterSerializer} in the opposite direction, sourced
 * by the composition root for the M9.1 integration spine's
 * {@code CommandRoutingSubscriber}.
 *
 * <p><strong>Why it lives in persistence.</strong> {@code integration-runtime}
 * carries no JSON library (DP-3) and the lifecycle module cannot read Jackson
 * under JPMS (persistence requires it non-transitively); this decoder rides the
 * exact mapper configuration the serializer used, so the parameters an adapter
 * receives round-trip faithfully with the at-rest encoding — including
 * value-model {@code AttributeValue}s via the AMD-52 codec.</p>
 *
 * <p><strong>Contract (DP-3).</strong> Null, blank, and {@code "{}"} inputs
 * decode to {@code Map.of()}; a malformed input logs a WARN and decodes to
 * {@code Map.of()} — never an exception on the caller's (bus) thread. Exposed
 * only as a {@link Function} through
 * {@code PersistenceFactory#commandParameterDecoder()} so Jackson stays off
 * persistence's exported API ({@code -Xlint:exports} / {@code -Werror}).
 * Thread-safe — the {@code ObjectMapper} is thread-safe after configuration.</p>
 */
final class CommandParameterDecoder implements Function<String, Map<String, Object>> {

    private static final Logger LOG = LoggerFactory.getLogger(CommandParameterDecoder.class);

    private static final TypeReference<Map<String, Object>> PARAMETER_MAP =
            new TypeReference<>() {
            };

    private final ObjectMapper mapper;

    /** Builds a decoder over a fresh, configuration-identical persistence mapper. */
    CommandParameterDecoder() {
        this.mapper = PersistenceObjectMapper.create();
    }

    @Override
    public Map<String, Object> apply(String parameters) {
        if (parameters == null || parameters.isBlank() || parameters.equals("{}")) {
            return Map.of();
        }
        try {
            Map<String, Object> decoded = mapper.readValue(parameters, PARAMETER_MAP);
            return decoded != null ? decoded : Map.of();
        } catch (Exception malformed) {
            LOG.warn("command parameter string is not a JSON object; decoding to an empty "
                    + "map: {}", parameters, malformed);
            return Map.of();
        }
    }
}
