/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.util.Objects;

/**
 * Condition that checks whether an entity's attribute equals a specified value.
 *
 * <p>Evaluated against the {@link com.homesynapse.state.StateSnapshot} captured at
 * trigger time.</p>
 *
 * <p>Defined in Doc 07 §3.8, §8.2.</p>
 *
 * @param selector  the entity selector to evaluate, never {@code null}
 * @param attribute the attribute name to check, never {@code null}
 * @param value     the expected attribute value, never {@code null}
 * @see ConditionDefinition
 * @see ConditionEvaluator
 */
public record StateCondition(
        Selector selector,
        String attribute,
        String value
) implements ConditionDefinition {

    /**
     * Validates non-null fields.
     *
     * @throws NullPointerException if any field is {@code null}
     */
    public StateCondition {
        Objects.requireNonNull(selector, "selector must not be null");
        Objects.requireNonNull(attribute, "attribute must not be null");
        Objects.requireNonNull(value, "value must not be null");
    }
}
