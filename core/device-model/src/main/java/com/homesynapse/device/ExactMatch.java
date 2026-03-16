/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

/**
 * An {@link Expectation} that is confirmed when the reported value equals
 * the expected value exactly.
 *
 * <p>Used for boolean and enum attributes where the commanded value must
 * match the reported value precisely (e.g., on/off state, energy direction).</p>
 *
 * @param expectedValue the value that must be matched exactly, never {@code null}
 * @see Expectation
 * @see ConfirmationMode#EXACT_MATCH
 * @since 1.0
 */
public record ExactMatch(AttributeValue expectedValue) implements Expectation {

    @Override
    public ConfirmationResult evaluate(AttributeValue reportedValue) {
        return expectedValue.equals(reportedValue)
                ? ConfirmationResult.CONFIRMED
                : ConfirmationResult.NOT_YET;
    }
}
