/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import com.homesynapse.event.EventEnvelope;
import com.homesynapse.state.StateSnapshot;

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
 * run-lifecycle half (M7.2a-1) builds and tests the lifecycle against a fake gate. The
 * production implementation (M7.2a-2, {@link StandardRunConditionGate}) wires the
 * {@link ConditionEvaluator} over the trigger-time {@link StateSnapshot} and publishes the
 * {@code automation_condition_evaluated} (AMD-92 row 4) diagnostic behind this same method.</p>
 *
 * <p><strong>Snapshot + envelope (M7.2a-2 widening).</strong> The FSM captures the
 * trigger-time {@link StateSnapshot} once (its {@code viewPosition} is
 * {@link RunContext#stateSnapshotPosition()}) and passes it here so a single snapshot drives
 * both the recorded position and condition evaluation (AMD-03). The triggering
 * {@link EventEnvelope} is supplied so the row-4 publish lands on the triggering event's
 * {@code CausalContext} (AMD-92 §2.4) — the {@link RunContext} does not carry the chain's
 * correlation id or event time.</p>
 *
 * <p>Implementations must be pure with respect to Run admission: returning a value and
 * (optionally) publishing the row-4 diagnostic, but never mutating FSM admission state.
 * Thread-safe — invoked concurrently from many Run-initiation paths.</p>
 *
 * @see RunManager
 * @see ConditionEvaluator
 */
@FunctionalInterface
public interface RunConditionGate {

    /**
     * Returns whether the automation's top-level conditions hold for this Run, evaluated
     * against the supplied trigger-time snapshot.
     *
     * @param automation      the automation whose conditions to evaluate, never {@code null}
     * @param context         the Run context (run id, resolved targets, snapshot position),
     *                        never {@code null}
     * @param triggeringEvent the event that triggered the Run, for causal stamping of the
     *                        row-4 diagnostic, never {@code null}
     * @param snapshot        the trigger-time state snapshot (its {@code viewPosition} equals
     *                        {@code context.stateSnapshotPosition()}), never {@code null}
     * @return {@code true} if the Run may proceed to RUNNING; {@code false} to terminate
     *         the Run with {@code CONDITION_NOT_MET}
     */
    boolean conditionsHold(AutomationDefinition automation, RunContext context,
                           EventEnvelope triggeringEvent, StateSnapshot snapshot);
}
