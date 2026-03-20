/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.util.Objects;

/**
 * Condition that checks whether a numeric attribute falls within a specified range.
 *
 * <p>At least one of {@code above} or {@code below} must be non-null. This constraint
 * is validated at YAML load time (Phase 3), not in the compact constructor.</p>
 *
 * <p>Evaluated against the {@link com.homesynapse.state.StateSnapshot} captured at
 * trigger time.</p>
 *
 * <p>Defined in Doc 07 §3.8, §8.2.</p>
 *
 * @param selector  the entity selector to evaluate, never {@code null}
 * @param attribute the numeric attribute name to check, never {@code null}
 * @param above     the lower bound; {@code null} if only upper bound is checked
 * @param below     the upper bound; {@code null} if only lower bound is checked
 * @see ConditionDefinition
 * @see ConditionEvaluator
 */
public record NumericCondition(
        Selector selector,
        String attribute,
        Double above,
        Double below
) implements ConditionDefinition {

    /**
     * Validates non-null fields.
     *
     * @throws NullPointerException if {@code selector} or {@code attribute} is {@code null}
     */
    public NumericCondition {
        Objects.requireNonNull(selector, "selector must not be null");
        Objects.requireNonNull(attribute, "attribute must not be null");
    }
}
