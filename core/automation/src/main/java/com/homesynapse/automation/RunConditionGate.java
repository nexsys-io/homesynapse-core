/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

/**
 * The EVALUATING-state decision seam: whether a Run's top-level conditions hold against
 * the trigger-time state (Doc 07 §3.6, §3.8).
 *
 * <p>The {@link RunManager} consults this gate at trigger time, <em>before</em>
 * concurrency-mode enforcement — a Run whose conditions are false completes immediately
 * with {@code CONDITION_NOT_MET} and never consumes a mode slot (the cross-module
 * condition-before-mode contract; prevents a {@code SINGLE} automation from blocking
 * itself on failed conditions).</p>
 *
 * <p><strong>Why a seam.</strong> This isolates the FSM from condition evaluation so the
 * run-lifecycle half (M7.2a-1) can build and test the lifecycle against a fake gate. The
 * production implementation (M7.2a-2) wires the {@link ConditionEvaluator} over the
 * trigger-time {@link com.homesynapse.state.StateSnapshot} and publishes the
 * {@code automation_condition_evaluated} (AMD-92 row 4) diagnostic behind this same
 * method — with zero FSM rework, because the call site is fixed here.</p>
 *
 * <p>Implementations must be pure with respect to Run admission: returning a value, not
 * mutating FSM state. Thread-safe — invoked concurrently from many Run-initiation paths.</p>
 *
 * @see RunManager
 * @see ConditionEvaluator
 */
@FunctionalInterface
public interface RunConditionGate {

    /**
     * Returns whether the automation's top-level conditions hold for this Run.
     *
     * @param automation the automation whose conditions to evaluate, never {@code null}
     * @param context    the Run context (resolved targets, snapshot position) for this
     *                   Run, never {@code null}
     * @return {@code true} if the Run may proceed to RUNNING; {@code false} to terminate
     *         the Run with {@code CONDITION_NOT_MET}
     */
    boolean conditionsHold(AutomationDefinition automation, RunContext context);
}
