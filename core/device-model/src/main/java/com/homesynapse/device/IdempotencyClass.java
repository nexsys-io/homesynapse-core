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

    /** Idempotent only when the pre-command state is known. A toggle command flips the current state, so retrying without state knowledge may produce the wrong outcome. */
    TOGGLE
}
