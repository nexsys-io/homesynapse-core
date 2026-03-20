/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.util.List;
import java.util.Objects;

/**
 * Logical disjunction of multiple conditions.
 *
 * <p>Evaluates sub-conditions sequentially and short-circuits on the first
 * {@code true} result. At least one condition must evaluate to {@code true}
 * for the disjunction to be {@code true}.</p>
 *
 * <p>Defined in Doc 07 §3.8, §8.2.</p>
 *
 * @param conditions the sub-conditions to disjoin, unmodifiable, never {@code null}
 * @see ConditionDefinition
 * @see ConditionEvaluator
 */
public record OrCondition(List<ConditionDefinition> conditions) implements ConditionDefinition {

    /**
     * Validates non-null and makes the list unmodifiable.
     *
     * @throws NullPointerException if {@code conditions} is {@code null}
     */
    public OrCondition {
        Objects.requireNonNull(conditions, "conditions must not be null");
        conditions = List.copyOf(conditions);
    }
}
