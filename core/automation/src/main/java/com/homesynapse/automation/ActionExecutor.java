/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.util.List;

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
     * @param actions the ordered list of actions to execute, never {@code null}
     * @param context the Run execution context, never {@code null}
     */
    void execute(List<ActionDefinition> actions, RunContext context);
}
