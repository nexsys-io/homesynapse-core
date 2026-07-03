/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

/**
 * Signals that the NCP answered an EZSP command with a non-success status
 * (1-byte EmberStatus on EZSP v13 and below; 32-bit {@code sl_status_t} on v14 —
 * D-M92-4).
 *
 * <p>Transient by classification (Doc 05 §3.7): the M9.1 supervisor's classifier
 * treats any non-{@code PermanentIntegrationException} runtime exception as
 * TRANSIENT.
 *
 * @see EzspCoordinatorProtocol
 */
class EzspCommandException extends RuntimeException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    private final int status;

    /**
     * Creates a command failure carrying the NCP status.
     *
     * @param message the failure description naming the command, Register C voice
     * @param status the NCP status value (EmberStatus or sl_status_t)
     */
    EzspCommandException(String message, int status) {
        super(message);
        this.status = status;
    }

    /**
     * Returns the NCP status value that caused this failure.
     *
     * @return the raw status (EmberStatus or sl_status_t width per negotiated version)
     */
    int status() {
        return status;
    }
}
