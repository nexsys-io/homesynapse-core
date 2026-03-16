/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

/**
 * An {@link Expectation} that is confirmed when the reported enum value
 * matches the expected transition target string.
 *
 * <p>Used for enum-typed attributes where a command transitions the device
 * to a specific named state.</p>
 *
 * @param expectedValue the expected enum string value, never {@code null}
 * @see Expectation
 * @see ConfirmationMode#ENUM_MATCH
 * @since 1.0
 */
public record EnumTransition(String expectedValue) implements Expectation {

    @Override
    public ConfirmationResult evaluate(AttributeValue reportedValue) {
        if (reportedValue instanceof EnumValue ev) {
            return expectedValue.equals(ev.value())
                    ? ConfirmationResult.CONFIRMED
                    : ConfirmationResult.NOT_YET;
        }
        return ConfirmationResult.NOT_YET;
    }
}
