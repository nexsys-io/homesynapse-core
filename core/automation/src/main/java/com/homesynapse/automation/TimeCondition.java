/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

/**
 * Condition that checks whether the current time falls within a specified window.
 *
 * <p>At least one of {@code after} or {@code before} must be non-null. This constraint
 * is validated at YAML load time (Phase 3), not in the compact constructor.
 * Time values are in {@code HH:MM} format (24-hour).</p>
 *
 * <p>Defined in Doc 07 §3.8, §8.2.</p>
 *
 * @param after  the start of the time window in HH:MM format; {@code null} if unbounded
 * @param before the end of the time window in HH:MM format; {@code null} if unbounded
 * @see ConditionDefinition
 * @see ConditionEvaluator
 */
public record TimeCondition(
        String after,
        String before
) implements ConditionDefinition {
}
