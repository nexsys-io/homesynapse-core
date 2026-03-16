/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

/**
 * An {@link Expectation} that is confirmed when the reported numeric value
 * is within ±tolerance of the target.
 *
 * <p>Used for brightness, color temperature, and other numeric attributes
 * where exact match is unreliable due to device rounding or hardware limits.</p>
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
        throw new UnsupportedOperationException("Implementation deferred to Phase 3");
    }
}
