/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

/**
 * Signals a malformed EZSP frame: too short, wrong direction bit, or an unsupported
 * frame format version.
 *
 * <p>Caught at the transport decode boundary, where the frame is discarded with a
 * structured WARN — malformed frames never cross the {@link CoordinatorTransport}
 * boundary as exceptions (its contract: invalid frames are silently discarded).
 *
 * @see EzspCodec
 */
class EzspFormatException extends RuntimeException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates a format rejection.
     *
     * @param message the rejection cause, Register C voice
     */
    EzspFormatException(String message) {
        super(message);
    }
}
