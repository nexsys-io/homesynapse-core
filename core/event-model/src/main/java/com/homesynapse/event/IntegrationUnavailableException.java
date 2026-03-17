/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

/**
 * Thrown when an integration adapter is unreachable, unresponsive, or in a
 * failed state and cannot process the requested operation.
 *
 * <p>This covers protocol-level failures (Zigbee coordinator offline, MQTT
 * broker unreachable), adapter lifecycle failures (adapter crashed and has
 * not recovered), and transient unavailability during integration restart.</p>
 *
 * <p>HTTP status 503 (Service Unavailable) is suggested because the failure
 * is in a downstream dependency, not in the request itself. The client may
 * retry after the integration recovers.</p>
 *
 * @see HomeSynapseException
 */
public class IntegrationUnavailableException extends HomeSynapseException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    private static final String ERROR_CODE = "integration.unavailable";
    private static final int HTTP_STATUS = 503;

    /**
     * Creates a new integration-unavailable exception.
     *
     * @param message the detail message describing which integration is unavailable
     */
    public IntegrationUnavailableException(String message) {
        super(message);
    }

    /**
     * Creates a new integration-unavailable exception with a cause.
     *
     * @param message the detail message describing which integration is unavailable
     * @param cause   the underlying cause (e.g., connection timeout, adapter crash)
     */
    public IntegrationUnavailableException(String message, Throwable cause) {
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
