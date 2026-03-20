/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

/**
 * Action steps that execute sequentially within a Run's virtual thread.
 *
 * <p>Each action step produces {@code automation_action_started} and
 * {@code automation_action_completed} events before and after execution.
 * Actions execute in order — the Run's virtual thread processes one action
 * at a time, enabling delay and wait-for semantics without blocking platform
 * threads (LTD-01).</p>
 *
 * <p>This sealed hierarchy permits five Tier 1 subtypes and three Tier 2 reserved
 * subtypes:</p>
 * <ul>
 *   <li>{@link CommandAction} — issue a command to target entities</li>
 *   <li>{@link DelayAction} — suspend the Run's virtual thread</li>
 *   <li>{@link WaitForAction} — block until a condition becomes true or timeout</li>
 *   <li>{@link ConditionBranchAction} — inline if/then/else branching</li>
 *   <li>{@link EmitEventAction} — produce a custom event on the event bus</li>
 *   <li>{@link ActivateSceneAction} — Tier 2 reserved</li>
 *   <li>{@link InvokeIntegrationAction} — Tier 2 reserved</li>
 *   <li>{@link ParallelAction} — Tier 2 reserved</li>
 * </ul>
 *
 * <p>All implementations are immutable records. Thread-safe.</p>
 *
 * <p>Defined in Doc 07 §3.9, §8.2.</p>
 *
 * @see ActionExecutor
 * @see AutomationDefinition
 */
public sealed interface ActionDefinition
        permits CommandAction, DelayAction, WaitForAction,
                ConditionBranchAction, EmitEventAction,
                ActivateSceneAction, InvokeIntegrationAction, ParallelAction {
}
