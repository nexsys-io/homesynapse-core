/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import com.homesynapse.value.AttributeValue;
import com.homesynapse.value.FloatValue;
import com.homesynapse.value.IntValue;
import com.homesynapse.value.QuantityValue;

/**
 * An {@link Expectation} that is confirmed when the reported numeric value
 * is within ±tolerance of the target.
 *
 * <p>Used for brightness, color temperature, and other numeric attributes
 * where exact match is unreliable due to device rounding or hardware limits.
 * Measured validation (bench 2026-07-01, AMD-97): the Wave-1 light re-derives
 * commanded color temperature ±1 mired, so exact match false-fails — the
 * capability-default ±50K band confirms the drift.</p>
 *
 * <p>The band is inclusive at both edges: {@code |reported − target| ≤ tolerance}
 * confirms. A non-numeric reported value is {@link ConfirmationResult#NOT_YET},
 * never a failure — the value may simply not be the authoritative report yet
 * (the {@link EnumTransition} precedent).</p>
 *
 * @param target the target numeric value
 * @param tolerance the acceptable deviation from the target (must be non-negative)
 * @see Expectation
 * @see ConfirmationMode#TOLERANCE
 * @since 1.0
 */
public record WithinTolerance(double target, double tolerance) implements Expectation {

    @Override
    public ConfirmationResult evaluate(AttributeValue reportedValue) {
        double reported;
        if (reportedValue instanceof IntValue iv) {
            reported = iv.value();
        } else if (reportedValue instanceof FloatValue fv) {
            reported = fv.value();
        } else if (reportedValue instanceof QuantityValue qv) {
            reported = qv.value();
        } else {
            return ConfirmationResult.NOT_YET;
        }
        return Math.abs(reported - target) <= tolerance
                ? ConfirmationResult.CONFIRMED
                : ConfirmationResult.NOT_YET;
    }
}
