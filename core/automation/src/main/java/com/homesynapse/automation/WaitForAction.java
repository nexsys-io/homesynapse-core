/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.time.Duration;
import java.util.Objects;

/**
 * Blocks the Run's virtual thread until a condition becomes true or a timeout expires.
 *
 * <p>The condition is polled at a configurable interval. If {@code pollInterval} is
 * {@code null}, the default poll interval from
 * {@code condition.wait_for_poll_interval_ms} configuration is used.</p>
 *
 * <p>Defined in Doc 07 §3.9, §8.2.</p>
 *
 * @param condition    the condition to wait for, never {@code null}
 * @param timeout      the maximum wait duration, never {@code null}
 * @param pollInterval the polling interval; {@code null} means use the configured default
 * @see ActionDefinition
 * @see ActionExecutor
 * @see ConditionEvaluator
 */
public record WaitForAction(
        ConditionDefinition condition,
        Duration timeout,
        Duration pollInterval
) implements ActionDefinition {

    /**
     * Validates non-null fields.
     *
     * @throws NullPointerException if {@code condition} or {@code timeout} is {@code null}
     */
    public WaitForAction {
        Objects.requireNonNull(condition, "condition must not be null");
        Objects.requireNonNull(timeout, "timeout must not be null");
    }
}
