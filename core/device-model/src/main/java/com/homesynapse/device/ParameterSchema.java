/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import java.util.Set;

/**
 * Describes a single parameter accepted by a device command.
 *
 * <p>Parameter schemas define the type, constraints, and feature-gate
 * requirements for each argument in a {@link CommandDefinition}. The
 * {@link CommandValidator} uses these schemas to validate command
 * parameters before dispatch.</p>
 *
 * @param parameterName the parameter name as used in command payloads, never {@code null}
 * @param type the data type of this parameter, never {@code null}
 * @param minimum the minimum allowed value for numeric parameters, {@code null} if unconstrained
 * @param maximum the maximum allowed value for numeric parameters, {@code null} if unconstrained
 * @param required whether this parameter must be present in every command invocation
 * @param requiredFeatures a bitmask of feature_map bits that must be set for this parameter to be available; {@code 0} means always available
 * @param validValues the set of allowed string values for {@link AttributeType#ENUM} parameters, {@code null} for non-enum types; unmodifiable when non-null
 * @see CommandDefinition
 * @see CommandValidator
 * @since 1.0
 */
public record ParameterSchema(
        String parameterName,
        AttributeType type,
        Number minimum,
        Number maximum,
        boolean required,
        int requiredFeatures,
        Set<String> validValues
) { }
