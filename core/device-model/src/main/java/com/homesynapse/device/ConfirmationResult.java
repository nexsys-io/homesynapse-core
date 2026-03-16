/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

/**
 * The outcome of evaluating a reported attribute value against an expected
 * command outcome in the Pending Command Ledger.
 *
 * <p>Returned by {@link Expectation#evaluate(AttributeValue)} to indicate
 * whether a pending command has been confirmed, is still waiting, has failed,
 * or has timed out.</p>
 *
 * <p>Defined in Doc 02 §3.8.</p>
 *
 * @see Expectation
 * @see ExpectedOutcome
 * @since 1.0
 */
public enum ConfirmationResult {

    /** The reported value satisfies the expectation. The command is confirmed. */
    CONFIRMED,

    /** The reported value does not yet satisfy the expectation. The command is still pending. */
    NOT_YET,

    /** The reported value indicates the command failed (e.g., device reported an error state). */
    FAILED,

    /** The confirmation window expired before a satisfying report was received. */
    TIMEOUT
}
