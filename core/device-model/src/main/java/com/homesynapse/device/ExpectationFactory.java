/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import java.util.Map;

/**
 * Factory for creating {@link Expectation} instances used by the Pending Command Ledger.
 *
 * <p>Given a capability, command type, command parameters, and the pre-command attribute
 * value, produces the appropriate expectation for evaluating command confirmation.</p>
 *
 * <p>Implementations must be safe for concurrent access.</p>
 *
 * <p>Defined in Doc 02 §8.1.</p>
 *
 * @see Expectation
 * @see com.homesynapse.event.StateConfirmedEvent
 * @since 1.0
 */
public interface ExpectationFactory {

    /**
     * Creates an expectation for evaluating command confirmation.
     *
     * @param capabilityId the capability identifier, never {@code null}
     * @param commandType the command type, never {@code null}
     * @param commandParameters the command parameters, never {@code null}
     * @param previousValue the attribute value before the command was issued, never {@code null}
     * @return the expectation for this command, never {@code null}
     * @throws IllegalArgumentException if the capability or command type is unknown
     */
    Expectation createExpectation(
            String capabilityId,
            String commandType,
            Map<String, Object> commandParameters,
            AttributeValue previousValue);
}
