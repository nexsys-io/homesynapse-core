/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import com.homesynapse.state.StateSnapshot;

/**
 * Evaluates condition predicates against current state.
 *
 * <p>All conditions within a Run are evaluated against a single
 * {@link StateSnapshot} captured at trigger time (AMD-03). This ensures
 * consistent evaluation even if state changes occur during the evaluation
 * window.</p>
 *
 * <p>Thread-safe. All methods may be called concurrently from multiple virtual threads.</p>
 *
 * <p>Defined in Doc 07 §3.8, §8.1.</p>
 *
 * @see ConditionDefinition
 * @see StateSnapshot
 */
public interface ConditionEvaluator {

    /**
     * Evaluates a single condition against the provided state snapshot.
     *
     * @param condition the condition to evaluate, never {@code null}
     * @param snapshot  the state snapshot captured at trigger time, never {@code null}
     * @return {@code true} if the condition is satisfied
     */
    boolean evaluate(ConditionDefinition condition, StateSnapshot snapshot);
}
