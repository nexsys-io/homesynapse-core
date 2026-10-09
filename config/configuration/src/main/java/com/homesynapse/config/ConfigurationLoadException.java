/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.config;

import com.homesynapse.event.HomeSynapseException;

/**
 * Thrown by {@link ConfigurationService#load()} when the loading pipeline
 * (Doc 06 §3.1) encounters {@link Severity#FATAL} or {@link Severity#ERROR}
 * validation issues (AMD-102) that prevent startup.
 *
 * <p>This exception signals that the configuration file is structurally
 * invalid, missing required sections, carries a value that fails its schema,
 * or carries a key the composed schema does not declare — the message names
 * every such path. The system cannot start until the configuration is
 * corrected.</p>
 *
 * <p>HTTP status 503 (Service Unavailable) is suggested because the system
 * is unable to serve requests without valid configuration.</p>
 *
 * @see ConfigurationService#load()
 * @see Severity#FATAL
 * @see HomeSynapseException
 */
public class ConfigurationLoadException extends HomeSynapseException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    private static final String ERROR_CODE = "config.load_failed";
    private static final int HTTP_STATUS = 503;

    /**
     * Creates a new configuration-load exception.
     *
     * @param message the detail message describing the load failure
     */
    public ConfigurationLoadException(String message) {
        super(message);
    }

    /**
     * Creates a new configuration-load exception with a cause.
     *
     * @param message the detail message describing the load failure
     * @param cause   the underlying cause
     */
    public ConfigurationLoadException(String message, Throwable cause) {
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
