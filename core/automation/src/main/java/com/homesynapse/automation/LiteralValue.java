/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.util.Objects;

import com.homesynapse.value.AttributeValue;

/**
 * A {@link ComputedValue} that resolves to a constant — its wrapped {@link AttributeValue},
 * independent of the snapshot or time (Doc 16 §3.2). The simplest permit: it lets a literal
 * typed value ride an action value position through the same resolution seam as the derived
 * permits, so the executor's input is uniformly concrete.
 *
 * @param value the constant this resolves to, never {@code null}
 * @see ComputedValue
 */
record LiteralValue(AttributeValue value) implements ComputedValue {

    /**
     * Validates the constant is present.
     *
     * @throws NullPointerException if {@code value} is {@code null}
     */
    LiteralValue {
        Objects.requireNonNull(value, "value must not be null");
    }

    @Override
    public AttributeValue resolve(ComputedValueContext ctx) {
        return value;
    }
}
