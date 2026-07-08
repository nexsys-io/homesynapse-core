/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Event-local mirror of the device-model {@code CommandDefinition} (AMD-99 §3,
 * full-fidelity). {@code idempotencyClass} flattens to the enum name;
 * {@link Duration} stays typed ({@code java.base}).
 *
 * <p>NOT an event: no {@code EventType} annotation, does not implement
 * {@link DomainEvent}.</p>
 *
 * @param commandType the command type identifier, never {@code null}
 * @param parameters the parameter-schema mirrors, never {@code null};
 *        order-preserving unmodifiable copy
 * @param requiredFeatures the feature_map bits required to execute; {@code 0} = always
 * @param expectedOutcomes the expected-outcome mirrors, never {@code null};
 *        order-preserving unmodifiable copy
 * @param defaultTimeout the default confirmation timeout, never {@code null}
 * @param idempotencyClass the {@code IdempotencyClass} enum name, never {@code null}
 * @see CapabilityInstanceRef
 */
public record CommandDefinitionRef(
        String commandType,
        List<ParameterSchemaRef> parameters,
        int requiredFeatures,
        List<ExpectedOutcomeRef> expectedOutcomes,
        Duration defaultTimeout,
        String idempotencyClass
) {

    /**
     * Validates required components and defensively copies the list components
     * (order-preserving — the domain components are ordered lists).
     *
     * @throws NullPointerException if any required component is {@code null}
     */
    public CommandDefinitionRef {
        Objects.requireNonNull(commandType, "commandType must not be null");
        Objects.requireNonNull(parameters, "parameters must not be null");
        Objects.requireNonNull(expectedOutcomes, "expectedOutcomes must not be null");
        Objects.requireNonNull(defaultTimeout, "defaultTimeout must not be null");
        Objects.requireNonNull(idempotencyClass, "idempotencyClass must not be null");
        parameters = List.copyOf(parameters);
        expectedOutcomes = List.copyOf(expectedOutcomes);
    }
}
