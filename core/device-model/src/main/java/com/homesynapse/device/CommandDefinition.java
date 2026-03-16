/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import java.time.Duration;
import java.util.List;

/**
 * Defines a command that can be issued to a device through a capability.
 *
 * <p>A command definition specifies the command type identifier, its parameters,
 * feature-gate requirements, expected outcomes for confirmation, the default
 * timeout, and the idempotency classification. The {@link CommandValidator}
 * validates command invocations against these definitions.</p>
 *
 * <p>Defined in Doc 02 §3.8.</p>
 *
 * @param commandType the command type identifier (e.g., "turn_on", "set_brightness"), never {@code null}
 * @param parameters the parameter schemas for this command, never {@code null}; unmodifiable
 * @param requiredFeatures a bitmask of feature_map bits required to execute this command; {@code 0} means always available
 * @param expectedOutcomes the expected attribute changes used for command confirmation, never {@code null}; unmodifiable
 * @param defaultTimeout the default timeout for command confirmation, never {@code null}
 * @param idempotencyClass the idempotency classification of this command, never {@code null}
 * @see Capability#commandDefinitions()
 * @see CommandValidator
 * @see ExpectedOutcome
 * @since 1.0
 */
public record CommandDefinition(
        String commandType,
        List<ParameterSchema> parameters,
        int requiredFeatures,
        List<ExpectedOutcome> expectedOutcomes,
        Duration defaultTimeout,
        IdempotencyClass idempotencyClass
) { }
