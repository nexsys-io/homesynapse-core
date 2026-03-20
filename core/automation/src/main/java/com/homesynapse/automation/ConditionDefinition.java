/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

/**
 * Boolean guards evaluated after a trigger fires, before actions execute.
 *
 * <p>Conditions check current state, not events. All conditions within a Run are
 * evaluated against a single {@link com.homesynapse.state.StateSnapshot} captured
 * at trigger time (AMD-03), ensuring consistent evaluation even if state changes
 * occur during the evaluation window.</p>
 *
 * <p>If any condition evaluates to {@code false}, the Run completes with
 * {@link RunStatus#CONDITION_NOT_MET} and does NOT consume a concurrency mode slot.</p>
 *
 * <p>This sealed hierarchy permits six Tier 1 subtypes and one Tier 2 reserved
 * subtype:</p>
 * <ul>
 *   <li>{@link StateCondition} — attribute equals a value</li>
 *   <li>{@link NumericCondition} — numeric range check</li>
 *   <li>{@link TimeCondition} — current time within a window</li>
 *   <li>{@link AndCondition} — logical conjunction (short-circuits on first false)</li>
 *   <li>{@link OrCondition} — logical disjunction (short-circuits on first true)</li>
 *   <li>{@link NotCondition} — logical negation</li>
 *   <li>{@link ZoneCondition} — Tier 2 reserved</li>
 * </ul>
 *
 * <p>All implementations are immutable records. Thread-safe.</p>
 *
 * <p>Defined in Doc 07 §3.8, §8.2.</p>
 *
 * @see ConditionEvaluator
 * @see AutomationDefinition
 */
public sealed interface ConditionDefinition
        permits StateCondition, NumericCondition, TimeCondition,
                AndCondition, OrCondition, NotCondition, ZoneCondition {
}
