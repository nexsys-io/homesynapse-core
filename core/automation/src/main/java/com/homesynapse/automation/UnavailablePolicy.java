/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

/**
 * Per-action behavior when a command targets an entity whose availability is
 * {@code offline}.
 *
 * <p>{@link #SKIP} is the default policy, applied at YAML load time if the user
 * omits the {@code on_unavailable} field. {@link #ERROR} causes the entire Run
 * to fail. {@link #WARN} dispatches the command anyway with a DIAGNOSTIC warning
 * event.</p>
 *
 * <p>Defined in Doc 07 §3.9, §8.2.</p>
 *
 * @see ActionExecutor
 * @see CommandAction
 */
public enum UnavailablePolicy {

    /**
     * Skip the command for offline entities.
     *
     * <p>Produces an {@code automation_action_completed} event with outcome
     * {@code skipped}. The Run continues with subsequent actions. This is the
     * default policy.</p>
     */
    SKIP,

    /**
     * Fail the Run when a target entity is offline.
     *
     * <p>The Run transitions to {@link RunStatus#FAILED}. No command is dispatched.</p>
     */
    ERROR,

    /**
     * Dispatch the command anyway with a DIAGNOSTIC warning event.
     *
     * <p>The command is sent to the integration adapter despite the entity being
     * offline. A DIAGNOSTIC event is produced to alert monitoring.</p>
     */
    WARN
}
