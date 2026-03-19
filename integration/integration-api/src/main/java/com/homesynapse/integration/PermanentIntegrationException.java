/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration;

import com.homesynapse.event.HomeSynapseException;

/**
 * Signals an unrecoverable adapter failure that the supervisor should not retry
 * (Doc 05 §3.7, §8.2).
 *
 * <p>When an adapter throws this exception from any lifecycle method
 * ({@link IntegrationAdapter#initialize()}, {@link IntegrationAdapter#run()},
 * or {@link IntegrationFactory#create(IntegrationContext)}), the supervisor
 * transitions the adapter directly to {@link HealthState#FAILED} without
 * attempting restart or recovery probing. This contrasts with transient
 * exceptions (any other {@link RuntimeException}), which trigger the normal
 * retry-with-backoff and suspension cycle.</p>
 *
 * <p>The exception message must be user-readable because it appears in the
 * observability dashboard and structured logs. Use Register C voice: direct,
 * neutral, no self-reference, no apology. Example:
 * {@code "Zigbee coordinator firmware version 1.2 is not supported; minimum required: 2.0"}.</p>
 *
 * <p>This exception extends {@link HomeSynapseException} to integrate with the
 * structured exception hierarchy established in the event model (Block D).</p>
 *
 * @see HomeSynapseException
 * @see IntegrationAdapter
 * @see HealthState#FAILED
 */
public class PermanentIntegrationException extends HomeSynapseException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    private static final String ERROR_CODE = "integration.permanent_failure";
    private static final int SUGGESTED_HTTP_STATUS = 503;

    /**
     * Creates a new permanent integration exception with the given detail message.
     *
     * @param message a user-readable description of the unrecoverable failure
     *                condition; should use Register C voice (direct, neutral)
     */
    public PermanentIntegrationException(String message) {
        super(message);
    }

    /**
     * Creates a new permanent integration exception with the given detail message
     * and underlying cause.
     *
     * @param message a user-readable description of the unrecoverable failure
     *                condition; should use Register C voice (direct, neutral)
     * @param cause   the underlying cause of this failure
     */
    public PermanentIntegrationException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public String errorCode() {
        return ERROR_CODE;
    }

    @Override
    public int suggestedHttpStatus() {
        return SUGGESTED_HTTP_STATUS;
    }
}
