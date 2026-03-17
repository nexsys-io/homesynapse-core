/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

/**
 * Thrown when a referenced entity does not exist in the system.
 *
 * <p>This is the general-purpose "not found" exception for any entity type
 * (device, automation, person, area, etc.). For device-specific lookups where
 * the caller knows the target is a device, prefer {@link DeviceNotFoundException}
 * for more precise error reporting.</p>
 *
 * @see DeviceNotFoundException
 * @see HomeSynapseException
 */
public class EntityNotFoundException extends HomeSynapseException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    private static final String ERROR_CODE = "entity.not_found";
    private static final int HTTP_STATUS = 404;

    /**
     * Creates a new entity-not-found exception.
     *
     * @param message the detail message identifying which entity was not found
     */
    public EntityNotFoundException(String message) {
        super(message);
    }

    /**
     * Creates a new entity-not-found exception with a cause.
     *
     * @param message the detail message identifying which entity was not found
     * @param cause   the underlying cause
     */
    public EntityNotFoundException(String message, Throwable cause) {
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
