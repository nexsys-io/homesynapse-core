/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import java.util.Map;

/**
 * Validates command parameters against capability-defined command schemas.
 *
 * <p>Used before command dispatch to ensure parameters conform to the type,
 * range, and feature-gate constraints defined by the capability's
 * {@link CommandDefinition} and {@link ParameterSchema}.</p>
 *
 * <p>Implementations must be safe for concurrent access.</p>
 *
 * <p>Defined in Doc 02 §8.1.</p>
 *
 * @see CommandDefinition
 * @see ParameterSchema
 * @since 1.0
 */
public interface CommandValidator {

    /**
     * Validates command parameters against the command's schema.
     *
     * @param capabilityId the capability identifier, never {@code null}
     * @param commandType the command type, never {@code null}
     * @param parameters the command parameters, never {@code null}
     * @param featureMap the feature bitmap of the target capability instance
     * @return the validation result, never {@code null}
     */
    ValidationResult validate(
            String capabilityId,
            String commandType,
            Map<String, Object> parameters,
            int featureMap);

    /**
     * Checks whether a command is supported by a capability instance with
     * the given feature map.
     *
     * @param capabilityId the capability identifier, never {@code null}
     * @param commandType the command type, never {@code null}
     * @param featureMap the feature bitmap of the target capability instance
     * @return {@code true} if the command is supported, {@code false} otherwise
     */
    boolean isCommandSupported(String capabilityId, String commandType, int featureMap);
}
