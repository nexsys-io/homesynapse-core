/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import com.homesynapse.value.AttributeType;

import java.util.List;
import java.util.Objects;

/**
 * Event-local mirror of the device-model {@code ParameterSchema} (AMD-99 §3,
 * full-fidelity). Same conventions as {@link AttributeSchemaRef}: typed
 * {@link AttributeType}, canonicalized {@code Number} bounds, nullable
 * {@code validValues} flattened to a sorted list.
 *
 * <p>NOT an event: no {@code EventType} annotation, does not implement
 * {@link DomainEvent}.</p>
 *
 * @param parameterName the parameter name as used in command payloads, never {@code null}
 * @param type the parameter data type, never {@code null}
 * @param minimum the minimum bound for numeric parameters, {@code null} if unconstrained
 * @param maximum the maximum bound for numeric parameters, {@code null} if unconstrained
 * @param required whether the parameter must be present in every invocation
 * @param requiredFeatures the feature_map bits gating this parameter; {@code 0} = always
 * @param validValues the allowed values for ENUM parameters, {@code null} for
 *        non-enum types; sorted unmodifiable copy when non-null
 * @see CommandDefinitionRef
 */
public record ParameterSchemaRef(
        String parameterName,
        AttributeType type,
        Number minimum,
        Number maximum,
        boolean required,
        int requiredFeatures,
        List<String> validValues
) {

    /**
     * Validates required components, canonicalizes the numeric bounds, and
     * sorts the nullable Set-derived value list.
     *
     * @throws NullPointerException if {@code parameterName} or {@code type} is {@code null}
     */
    public ParameterSchemaRef {
        Objects.requireNonNull(parameterName, "parameterName must not be null");
        Objects.requireNonNull(type, "type must not be null");
        minimum = PayloadMirrors.canonicalNumber(minimum);
        maximum = PayloadMirrors.canonicalNumber(maximum);
        validValues = PayloadMirrors.sortedCopyOrNull(validValues);
    }
}
