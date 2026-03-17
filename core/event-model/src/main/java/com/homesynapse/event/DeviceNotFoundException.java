/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

/**
 * Thrown when a referenced device does not exist in the system.
 *
 * <p>This is a device-specific specialization of {@link EntityNotFoundException}
 * that produces a distinct error code ({@code "device.not_found"}) for more
 * precise API error responses and log filtering. Use this when the caller
 * explicitly requested a device by ID and the device does not exist.</p>
 *
 * @see EntityNotFoundException
 * @see HomeSynapseException
 */
public class DeviceNotFoundException extends HomeSynapseException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    private static final String ERROR_CODE = "device.not_found";
    private static final int HTTP_STATUS = 404;

    /**
     * Creates a new device-not-found exception.
     *
     * @param message the detail message identifying which device was not found
     */
    public DeviceNotFoundException(String message) {
        super(message);
    }

    /**
     * Creates a new device-not-found exception with a cause.
     *
     * @param message the detail message identifying which device was not found
     * @param cause   the underlying cause
     */
    public DeviceNotFoundException(String message, Throwable cause) {
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
