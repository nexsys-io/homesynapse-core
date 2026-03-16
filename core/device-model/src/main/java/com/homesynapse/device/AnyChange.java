/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

/**
 * An {@link Expectation} that is confirmed when the reported value differs
 * from the pre-command value.
 *
 * <p>Used for toggle commands where the target is "not what it was" — the
 * expectation is satisfied when the device reports any value different from
 * the state captured before the command was issued.</p>
 *
 * @param previousValue the attribute value captured before the command was issued, never {@code null}
 * @see Expectation
 * @see ConfirmationMode#ANY_CHANGE
 * @since 1.0
 */
public record AnyChange(AttributeValue previousValue) implements Expectation {

    @Override
    public ConfirmationResult evaluate(AttributeValue reportedValue) {
        return !previousValue.equals(reportedValue)
                ? ConfirmationResult.CONFIRMED
                : ConfirmationResult.NOT_YET;
    }
}
