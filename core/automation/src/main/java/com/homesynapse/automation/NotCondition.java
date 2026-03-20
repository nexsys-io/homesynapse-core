/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.util.Objects;

/**
 * Logical negation of a single condition.
 *
 * <p>Returns {@code true} if the wrapped condition evaluates to {@code false},
 * and vice versa.</p>
 *
 * <p>Defined in Doc 07 §3.8, §8.2.</p>
 *
 * @param condition the condition to negate, never {@code null}
 * @see ConditionDefinition
 * @see ConditionEvaluator
 */
public record NotCondition(ConditionDefinition condition) implements ConditionDefinition {

    /**
     * Validates non-null.
     *
     * @throws NullPointerException if {@code condition} is {@code null}
     */
    public NotCondition {
        Objects.requireNonNull(condition, "condition must not be null");
    }
}
