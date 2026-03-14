/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

package com.homesynapse.event;

/**
 * Idempotency classification for commands, governing replay behavior on crash recovery.
 *
 * <p>When HomeSynapse restarts and discovers {@code command_issued} events without
 * corresponding {@code command_result} events (pending commands at crash time), the
 * idempotency class determines whether the command is automatically re-issued
 * (Doc 01 §3.7).</p>
 *
 * <p>The integration adapter declares idempotency per command type at registration.
 * This is the split between platform mechanism and integration policy — the platform
 * provides the recovery framework, the integration declares each command's safety
 * characteristics.</p>
 *
 * @see <a href="Doc 01 §3.7">Command Idempotency for Crash Recovery</a>
 */
public enum CommandIdempotency {

    /**
     * Command can be safely re-issued after restart.
     *
     * <p>Re-issued automatically after the subscriber reaches {@link ProcessingMode#LIVE}
     * mode. Examples: {@code set_level(75%)}, {@code lock_door()},
     * {@code set_temperature(72)}.</p>
     */
    IDEMPOTENT,

    /**
     * Command must not be re-issued — repeating it would produce a different outcome.
     *
     * <p>Marked as EXPIRED with {@code command_result} status {@code expired_on_restart}.
     * Examples: {@code toggle()}, {@code increment()}, {@code cycle_color()}.</p>
     */
    NOT_IDEMPOTENT,

    /**
     * Re-issue decision is delegated to the integration adapter.
     *
     * <p>Offered to the integration adapter on restart, which decides based on current
     * device state. Used for protocol-specific commands where idempotency depends on
     * runtime conditions.</p>
     */
    CONDITIONAL
}
