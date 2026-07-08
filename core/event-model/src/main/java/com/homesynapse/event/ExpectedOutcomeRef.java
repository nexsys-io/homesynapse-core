/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import java.util.Objects;

/**
 * Event-local mirror of the device-model {@code ExpectedOutcome} — maps an
 * attribute to the confirmation logic for command evaluation (AMD-99 §3).
 *
 * <p>NOT an event: no {@code EventType} annotation, does not implement
 * {@link DomainEvent}.</p>
 *
 * @param attributeKey the attribute key monitored for confirmation, never {@code null}
 * @param expectation the evaluation-strategy mirror, never {@code null}
 * @param timeoutMs the maximum time in milliseconds to wait for confirmation
 * @see CommandDefinitionRef
 * @see ExpectationRef
 */
public record ExpectedOutcomeRef(
        String attributeKey,
        ExpectationRef expectation,
        long timeoutMs
) {

    /**
     * Validates required components.
     *
     * @throws NullPointerException if {@code attributeKey} or {@code expectation}
     *         is {@code null}
     */
    public ExpectedOutcomeRef {
        Objects.requireNonNull(attributeKey, "attributeKey must not be null");
        Objects.requireNonNull(expectation, "expectation must not be null");
    }
}
