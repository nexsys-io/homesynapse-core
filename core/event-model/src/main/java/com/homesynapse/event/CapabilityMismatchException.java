/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

/**
 * Thrown when a command or operation targets a capability that the entity does not
 * support or that conflicts with the entity's current state.
 *
 * <p>Examples include issuing a {@code set_brightness} command to a device that
 * only supports {@code on_off}, or attempting to set a color temperature on a
 * device whose color mode is currently {@code rgb}.</p>
 *
 * <p>HTTP status 409 (Conflict) is suggested because the request is syntactically
 * valid but conflicts with the entity's current capability set or state.</p>
 *
 * @see HomeSynapseException
 */
public class CapabilityMismatchException extends HomeSynapseException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    private static final String ERROR_CODE = "capability.mismatch";
    private static final int HTTP_STATUS = 409;

    /**
     * Creates a new capability-mismatch exception.
     *
     * @param message the detail message describing the mismatch
     */
    public CapabilityMismatchException(String message) {
        super(message);
    }

    /**
     * Creates a new capability-mismatch exception with a cause.
     *
     * @param message the detail message describing the mismatch
     * @param cause   the underlying cause
     */
    public CapabilityMismatchException(String message, Throwable cause) {
        super(message, cause);
    }

    /** {@inheritDoc} */
    @Override
    public String errorCode() {
        return ERROR_CODE;
    }

    /** {@inheritDoc} */
    @Override
    public int suggestedHttpStatus() {
        return HTTP_STATUS;
    }
}
