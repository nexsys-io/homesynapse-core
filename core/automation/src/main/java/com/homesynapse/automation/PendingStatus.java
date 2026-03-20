/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

/**
 * Lifecycle state of a command tracked by the {@link PendingCommandLedger}.
 *
 * <p>Commands progress through: {@link #DISPATCHED} (initial, after {@code command_issued})
 * &rarr; {@link #ACKNOWLEDGED} (adapter acknowledged receipt) &rarr; {@link #CONFIRMED}
 * (state confirmation matched expectation) or {@link #TIMED_OUT} (deadline expired).
 * {@link #EXPIRED} is used for {@code NOT_IDEMPOTENT} commands discovered pending after
 * a system restart.</p>
 *
 * <p>Defined in Doc 07 §4.3, §8.2.</p>
 *
 * @see PendingCommandLedger
 * @see PendingCommand
 */
public enum PendingStatus {

    /**
     * Command has been issued and dispatched to the integration adapter.
     *
     * <p>This is the initial state after a {@code command_issued} event is processed.</p>
     */
    DISPATCHED,

    /**
     * The integration adapter has acknowledged receipt of the command.
     *
     * <p>Acknowledgment indicates the adapter accepted the command for processing
     * but the expected state change has not yet been observed.</p>
     */
    ACKNOWLEDGED,

    /**
     * The expected state change has been confirmed via a matching {@code state_reported} event.
     *
     * <p>Terminal state. The {@link PendingCommandLedger} produces a {@code state_confirmed}
     * event when this transition occurs.</p>
     */
    CONFIRMED,

    /**
     * The command's deadline expired without confirmation. Terminal state.
     *
     * <p>The {@link PendingCommandLedger} produces a {@code command_confirmation_timed_out}
     * event when this transition occurs.</p>
     */
    TIMED_OUT,

    /**
     * The command was pending at system restart and is classified as
     * {@link com.homesynapse.event.CommandIdempotency#NOT_IDEMPOTENT}. Terminal state.
     *
     * <p>Non-idempotent commands cannot be safely re-issued after restart, so they
     * are marked expired with a {@code command_result} status of
     * {@code expired_on_restart}.</p>
     */
    EXPIRED
}
