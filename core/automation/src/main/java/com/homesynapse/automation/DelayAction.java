/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.time.Duration;
import java.util.Objects;

/**
 * Suspends the Run's virtual thread for a specified duration.
 *
 * <p>Virtual threads do not consume platform threads during sleep (LTD-01),
 * so delays have negligible resource cost. Cancellation (e.g., when
 * {@link ConcurrencyMode#RESTART} cancels an active Run) is performed via
 * {@link Thread#interrupt()} on the Run's virtual thread.</p>
 *
 * <p>Defined in Doc 07 §3.9, §8.2.</p>
 *
 * @param duration the delay duration, never {@code null}
 * @see ActionDefinition
 * @see ActionExecutor
 */
public record DelayAction(Duration duration) implements ActionDefinition {

    /**
     * Validates non-null.
     *
     * @throws NullPointerException if {@code duration} is {@code null}
     */
    public DelayAction {
        Objects.requireNonNull(duration, "duration must not be null");
    }
}
