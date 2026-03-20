/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.util.List;
import java.util.Objects;

/**
 * Inline decision branching within an action sequence.
 *
 * <p>If the condition evaluates to {@code true}, the {@code thenActions} execute.
 * Otherwise, the {@code elseActions} execute (which may be empty).</p>
 *
 * <p>Defined in Doc 07 §3.9, §8.2.</p>
 *
 * @param condition   the branch condition, never {@code null}
 * @param thenActions actions to execute if condition is true, unmodifiable, never {@code null}
 * @param elseActions actions to execute if condition is false, unmodifiable,
 *                    never {@code null} (may be empty)
 * @see ActionDefinition
 * @see ActionExecutor
 * @see ConditionEvaluator
 */
public record ConditionBranchAction(
        ConditionDefinition condition,
        List<ActionDefinition> thenActions,
        List<ActionDefinition> elseActions
) implements ActionDefinition {

    /**
     * Validates non-null fields and makes the lists unmodifiable.
     *
     * @throws NullPointerException if any field is {@code null}
     */
    public ConditionBranchAction {
        Objects.requireNonNull(condition, "condition must not be null");
        Objects.requireNonNull(thenActions, "thenActions must not be null");
        Objects.requireNonNull(elseActions, "elseActions must not be null");
        thenActions = List.copyOf(thenActions);
        elseActions = List.copyOf(elseActions);
    }
}
