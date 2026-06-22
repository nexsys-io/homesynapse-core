/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.util.List;

import com.homesynapse.event.EventEnvelope;

/**
 * Executes action steps sequentially within a Run's virtual thread.
 *
 * <p>Each action step produces {@code automation_action_started} and
 * {@code automation_action_completed} events. Command actions route through the
 * Command Pipeline (§3.11) via {@link CommandDispatchService}. Delay actions
 * suspend the virtual thread. The executor handles all action types defined in
 * the {@link ActionDefinition} hierarchy.</p>
 *
 * <p>Thread-safe per-Run: each Run executes on its own virtual thread. Multiple
 * Runs may execute concurrently, each with its own executor invocation.</p>
 *
 * <p>Defined in Doc 07 §3.9, §8.1.</p>
 *
 * @see ActionDefinition
 * @see CommandDispatchService
 * @see RunManager
 */
public interface ActionExecutor {

    /**
     * Executes the action sequence within the Run's virtual thread.
     *
     * <p>Returns an {@link ActionExecutionResult} tally (DP-D) — the real
     * {@code actionCount}/{@code commandCount} the FSM stamps onto
     * {@code automation_completed}, plus the §6.2 fail-fast reason (non-{@code null} ⇒ the
     * sequence stopped at a failing action and the Run terminates {@code FAILED}). The
     * triggering {@link EventEnvelope} is supplied so the per-action
     * {@code automation_action_started}/{@code automation_action_completed} diagnostics
     * publish on the triggering event's {@code CausalContext} (AMD-92 §2.4) — the
     * {@link RunContext} carries the triggering event id and automation id but not the
     * chain's correlation id or event time.</p>
     *
     * @param actions         the ordered list of actions to execute, never {@code null}
     * @param context         the Run execution context, never {@code null}
     * @param triggeringEvent the event that triggered the Run, for causal stamping of the
     *                        action diagnostics, never {@code null}
     * @return the action/command tally and §6.2 outcome, never {@code null}
     */
    ActionExecutionResult execute(List<ActionDefinition> actions, RunContext context,
                                  EventEnvelope triggeringEvent);
}
