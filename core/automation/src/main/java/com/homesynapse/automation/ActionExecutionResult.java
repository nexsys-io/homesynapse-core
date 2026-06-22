/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.util.Objects;

/**
 * The tally an {@link ActionExecutor} reports back to the {@link RunManager} FSM for one
 * Run's action sequence (DP-D): the real {@code actionCount}/{@code commandCount} that
 * populate {@code automation_completed} (Doc 07 §3.7), plus the §6.2 fail-fast outcome.
 *
 * <p>A {@code void} return could not carry the counts, and the counts are needed on
 * <em>both</em> the COMPLETED and FAILED terminal transitions — so a partial tally up to a
 * failing action still reports the work done. The {@link #failureReason()} channel
 * (non-{@code null} ⇒ §6.2 fail-fast) lets the FSM record the terminal reason without the
 * executor having to throw, keeping the tally available on every path.</p>
 *
 * <p>Automation-resident — it never crosses the event boundary (the flattened counts ride
 * {@code automation_completed}), so exposing it on {@link ActionExecutor}'s public API
 * introduces no JPMS edge.</p>
 *
 * @param actionCount   the number of top-level actions executed (or attempted up to a
 *                      failure); {@code >= 0}
 * @param commandCount  the number of commands dispatched across all command actions;
 *                      {@code >= 0}
 * @param failureReason the §6.2 fail-fast reason; {@code null} when the sequence completed
 *                      (or was interrupted — the FSM detects an abort via the thread's
 *                      interrupt status, not this field)
 * @see ActionExecutor
 * @see RunManager
 */
public record ActionExecutionResult(int actionCount, int commandCount, String failureReason) {

    /**
     * Validates non-negative counts. {@code failureReason} is nullable.
     *
     * @throws IllegalArgumentException if {@code actionCount} or {@code commandCount} is
     *                                  negative
     */
    public ActionExecutionResult {
        if (actionCount < 0) {
            throw new IllegalArgumentException("actionCount must be >= 0: " + actionCount);
        }
        if (commandCount < 0) {
            throw new IllegalArgumentException("commandCount must be >= 0: " + commandCount);
        }
    }

    /**
     * A successful tally (no §6.2 failure).
     *
     * @param actionCount  the actions executed; {@code >= 0}
     * @param commandCount the commands dispatched; {@code >= 0}
     * @return a result with {@code failureReason == null}, never {@code null}
     */
    public static ActionExecutionResult succeeded(int actionCount, int commandCount) {
        return new ActionExecutionResult(actionCount, commandCount, null);
    }

    /**
     * A fail-fast tally: the sequence stopped at a failing action (§6.2).
     *
     * @param actionCount   the actions attempted up to and including the failure; {@code >= 0}
     * @param commandCount  the commands dispatched before the failure; {@code >= 0}
     * @param failureReason the failure description, never {@code null} or blank
     * @return a result carrying the failure reason, never {@code null}
     * @throws NullPointerException     if {@code failureReason} is {@code null}
     * @throws IllegalArgumentException if {@code failureReason} is blank
     */
    public static ActionExecutionResult failed(int actionCount, int commandCount,
                                               String failureReason) {
        Objects.requireNonNull(failureReason, "failureReason must not be null");
        if (failureReason.isBlank()) {
            throw new IllegalArgumentException("failureReason must not be blank");
        }
        return new ActionExecutionResult(actionCount, commandCount, failureReason);
    }

    /**
     * Whether the action sequence failed fast (§6.2).
     *
     * @return {@code true} if {@link #failureReason()} is non-{@code null}
     */
    public boolean failed() {
        return failureReason != null;
    }
}
