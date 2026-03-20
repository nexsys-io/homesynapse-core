/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

/**
 * Lifecycle state of an automation Run.
 *
 * <p>{@link #EVALUATING} and {@link #RUNNING} are transient (active) states — the Run
 * is consuming a concurrency mode slot. {@link #COMPLETED}, {@link #FAILED},
 * {@link #ABORTED}, and {@link #CONDITION_NOT_MET} are terminal states — the Run
 * has released its slot and will not transition again.</p>
 *
 * <p>Defined in Doc 07 §3.7, §8.2.</p>
 *
 * @see RunManager
 * @see RunContext
 */
public enum RunStatus {

    /**
     * The Run is evaluating conditions against the trigger-time state snapshot.
     *
     * <p>This is the initial transient state. If conditions pass, transitions to
     * {@link #RUNNING}. If conditions fail, transitions to {@link #CONDITION_NOT_MET}.</p>
     */
    EVALUATING,

    /**
     * The Run is executing its action sequence on a virtual thread.
     *
     * <p>Transitions to {@link #COMPLETED} on successful execution of all actions,
     * {@link #FAILED} on unrecoverable error, or {@link #ABORTED} on cancellation
     * (e.g., {@link ConcurrencyMode#RESTART} interrupt).</p>
     */
    RUNNING,

    /**
     * All actions executed successfully. Terminal state.
     */
    COMPLETED,

    /**
     * An unrecoverable error occurred during action execution. Terminal state.
     */
    FAILED,

    /**
     * The Run was cancelled before completion. Terminal state.
     *
     * <p>Typically caused by {@link ConcurrencyMode#RESTART} cancelling an active Run,
     * or by system shutdown.</p>
     */
    ABORTED,

    /**
     * Conditions evaluated to {@code false} — the Run did not execute any actions. Terminal state.
     *
     * <p>A Run that completes with this status does NOT consume a concurrency mode slot,
     * preventing {@link ConcurrencyMode#SINGLE} automations from blocking on failed
     * conditions.</p>
     */
    CONDITION_NOT_MET
}
