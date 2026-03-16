/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

/**
 * Evaluation contract for the Pending Command Ledger's command confirmation system.
 *
 * <p>Given a reported attribute value from a device, an expectation determines
 * whether a pending command has been confirmed. The sealed hierarchy ensures
 * exhaustive handling of all confirmation strategies in {@code switch} expressions.</p>
 *
 * <p>Implementations are immutable records carrying the expected state. The
 * {@link #evaluate(AttributeValue)} method compares the reported value against
 * the expectation and returns a {@link ConfirmationResult}.</p>
 *
 * <p>Defined in Doc 02 §3.8.</p>
 *
 * @see ConfirmationResult
 * @see ExpectedOutcome
 * @see com.homesynapse.event.StateConfirmedEvent
 * @since 1.0
 */
public sealed interface Expectation
        permits ExactMatch, WithinTolerance, EnumTransition, AnyChange {

    /**
     * Evaluates a reported attribute value against this expectation.
     *
     * @param reportedValue the attribute value reported by the device, never {@code null}
     * @return the confirmation result, never {@code null}
     */
    ConfirmationResult evaluate(AttributeValue reportedValue);
}
