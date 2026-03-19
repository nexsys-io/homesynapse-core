/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.config;

import com.homesynapse.event.HomeSynapseException;

/**
 * Thrown by {@link ConfigurationService#reload()} when the candidate
 * configuration contains {@link Severity#FATAL} or {@link Severity#ERROR}
 * validation issues (Doc 06 §3.3).
 *
 * <p>When a reload is rejected, the active {@link ConfigModel} remains
 * unchanged — this is the atomicity guarantee of the reload pipeline. The
 * stricter rejection policy on reload (ERROR causes rejection, unlike startup
 * where ERROR keys revert to defaults) exists because on reload there is a
 * prior good state to preserve.</p>
 *
 * <p>HTTP status 422 (Unprocessable Entity) is suggested because the
 * candidate configuration is syntactically valid YAML but semantically
 * invalid against the JSON Schema.</p>
 *
 * @see ConfigurationService#reload()
 * @see Severity#FATAL
 * @see Severity#ERROR
 * @see HomeSynapseException
 */
public class ConfigurationReloadException extends HomeSynapseException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    private static final String ERROR_CODE = "config.reload_failed";
    private static final int HTTP_STATUS = 422;

    /**
     * Creates a new configuration-reload exception.
     *
     * @param message the detail message describing the reload failure
     */
    public ConfigurationReloadException(String message) {
        super(message);
    }

    /**
     * Creates a new configuration-reload exception with a cause.
     *
     * @param message the detail message describing the reload failure
     * @param cause   the underlying cause
     */
    public ConfigurationReloadException(String message, Throwable cause) {
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
