/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

/**
 * Classifies the idempotency semantics of a device command.
 *
 * <p>The idempotency class determines whether a command can be safely retried
 * by the Pending Command Ledger without causing unintended side effects.
 * This classification is essential for reliable command confirmation in
 * environments where message delivery is not guaranteed.</p>
 *
 * <p>Defined in Doc 02 §3.8.</p>
 *
 * @see CommandDefinition
 * @see Expectation
 * @since 1.0
 */
public enum IdempotencyClass {

    /** Safe to retry without side effects. Repeated invocations produce the same device state. */
    IDEMPOTENT,

    /** Unsafe to retry. Each invocation may produce a different effect (e.g., increment, trigger). */
    NOT_IDEMPOTENT,

    /**
     * Re-issue decision is delegated to the integration adapter.
     *
     * <p>On crash recovery, the Pending Command Ledger offers the command to the
     * integration adapter, which decides whether re-issuing is safe based on
     * current device state. Used for commands where idempotency depends on
     * runtime conditions (e.g., toggle operations where the current state
     * determines whether re-issuing produces the intended outcome).</p>
     *
     * @see com.homesynapse.event.CommandIdempotency#CONDITIONAL
     */
    CONDITIONAL
}
