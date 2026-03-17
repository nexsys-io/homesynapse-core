/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

/**
 * Thrown when a configuration value fails validation against its JSON Schema
 * or against subsystem-specific constraints (LTD-09).
 *
 * <p>This covers YAML configuration files, runtime configuration updates via
 * the REST API, and automation definition validation. The detail message should
 * include the configuration path and the specific validation failure.</p>
 *
 * <p>HTTP status 422 (Unprocessable Entity) is suggested because the request
 * is syntactically valid but semantically incorrect.</p>
 *
 * @see HomeSynapseException
 */
public class ConfigurationValidationException extends HomeSynapseException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    private static final String ERROR_CODE = "config.validation_failed";
    private static final int HTTP_STATUS = 422;

    /**
     * Creates a new configuration-validation exception.
     *
     * @param message the detail message describing the validation failure
     */
    public ConfigurationValidationException(String message) {
        super(message);
    }

    /**
     * Creates a new configuration-validation exception with a cause.
     *
     * @param message the detail message describing the validation failure
     * @param cause   the underlying cause
     */
    public ConfigurationValidationException(String message, Throwable cause) {
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
