/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Serializes an automation command's parameter map to its JSON object string using the
 * persistence {@link ObjectMapper} (the configuration {@link PersistenceObjectMapper#create()}
 * builds, which registers the AMD-52 {@code AttributeValue} codec via
 * {@link PersistenceJacksonModule}).
 *
 * <p><strong>Why it lives in persistence.</strong> {@code command_issued.parameters} is a JSON
 * string (AMD-95), but {@code com.homesynapse.automation} carries no JSON library; the
 * composition root sources this serializer from {@link PersistenceFactory} so a command's
 * resolved parameters — literals and value-model {@code AttributeValue}s (the M7.2b
 * {@code ComputedValue} resolution yields {@code AttributeValue}/literals) — round-trip with
 * exactly the same encoding the at-rest payload path and the future integration adapter use,
 * with no new module edge and no hand-rolled JSON writer that would diverge from the
 * {@code AttributeValue} serializers.</p>
 *
 * <p><strong>Why a {@code Function}, not an {@code ObjectMapper}.</strong> This class is
 * package-private and is exposed only as a {@link Function} through
 * {@link PersistenceFactory#commandParameterSerializer()}. Returning the {@code ObjectMapper}
 * would put Jackson on persistence's exported API surface, forcing
 * {@code requires transitive com.fasterxml.jackson.databind} ({@code -Xlint:exports} /
 * {@code -Werror}); the {@code java.util.function.Function} keeps Jackson internal.</p>
 *
 * <p><strong>Contract.</strong> Total over any {@code Map<String, Object>} whose values are
 * JSON-serializable by the persistence mapper (literals + {@code AttributeValue}). An empty
 * map serializes to {@code "{}"}; a non-serializable value fails closed with an
 * {@link IllegalArgumentException} (the executor already floors blank/null output to
 * {@code "{}"}). Thread-safe — the {@code ObjectMapper} is thread-safe after configuration.</p>
 */
final class CommandParameterSerializer implements Function<Map<String, Object>, String> {

    private final ObjectMapper mapper;

    /** Builds a serializer over a fresh, configuration-identical persistence mapper. */
    CommandParameterSerializer() {
        this.mapper = PersistenceObjectMapper.create();
    }

    @Override
    public String apply(Map<String, Object> parameters) {
        Objects.requireNonNull(parameters, "parameters");
        try {
            return mapper.writeValueAsString(parameters);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException(
                    "command parameter map is not JSON-serializable: " + parameters, ex);
        }
    }
}
