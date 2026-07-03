/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

/**
 * Signals a terminal failure of the serial transport substrate: the ASH session entered
 * FAILED (consecutive ACK-timeout exhaustion, an NCP ERROR frame, an unexpected reset)
 * or the underlying port died (read/write error, unplug).
 *
 * <p>This is the transport-failure signal named by Doc 08 §3.3 — surfaced exactly once
 * per failure event (no failure storms); subsequent operations on a FAILED session are
 * fast-rejected with {@link IllegalStateException}. Recovery is the reset/reopen path
 * ({@link AshSession#connect()}, {@link PortWatchdog}).
 *
 * <p>Deliberately a {@code RuntimeException} (not {@code PermanentIntegrationException}):
 * the M9.1 supervisor's exception classifier treats it as TRANSIENT, driving the normal
 * restart-with-backoff cycle (Doc 05 §3.7).
 */
class TransportFailureException extends RuntimeException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates a transport failure signal.
     *
     * @param message the failure description with diagnostics (last frame, counters),
     *                Register C voice
     */
    TransportFailureException(String message) {
        super(message);
    }

    /**
     * Creates a transport failure signal with an underlying cause.
     *
     * @param message the failure description, Register C voice
     * @param cause the underlying cause
     */
    TransportFailureException(String message, Throwable cause) {
        super(message, cause);
    }
}
