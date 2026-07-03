/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

/**
 * Signals that a single EZSP command did not receive its response within the
 * per-command timeout (D-M92-5). Surfaces per-command — caller policy decides
 * (M9.4 maps it to {@code command_result}); a missed watchdog keepalive feeds
 * ASH-liveness instead of throwing.
 *
 * @see EzspCoordinatorProtocol
 */
class EzspCommandTimeoutException extends RuntimeException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates a per-command timeout failure.
     *
     * @param frameId the EZSP frame ID of the timed-out command
     * @param elapsedMillis the time waited before giving up
     */
    EzspCommandTimeoutException(int frameId, long elapsedMillis) {
        super(String.format(
                "EZSP command 0x%04X received no response within %d ms",
                frameId, elapsedMillis));
    }
}
