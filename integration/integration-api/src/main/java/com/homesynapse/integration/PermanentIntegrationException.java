/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration;

import com.homesynapse.event.HomeSynapseException;

import java.util.regex.Pattern;

/**
 * Signals an unrecoverable adapter failure that the supervisor should not retry
 * (Doc 05 §3.7, §8.2).
 *
 * <p>When an adapter throws this exception from any lifecycle method
 * ({@link IntegrationAdapter#initialize()}, {@link IntegrationAdapter#run()},
 * {@link IntegrationAdapter#migrate(int, int)}, or
 * {@link IntegrationFactory#create(IntegrationContext)}), the supervisor
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
 * <h2>Error codes (AMD-56)</h2>
 *
 * <p>The two original constructors yield the default code
 * {@code integration.permanent_failure}. The append-only code-bearing
 * constructor pair lets an adapter (or the M9 classifier) carry a specific
 * well-known code — for example {@code integration.auth_failed}, which the
 * supervisor maps to {@link com.homesynapse.integration.runtime.ExceptionClassification#AUTH_FAILED}.
 * A supplied {@code errorCode} must be a non-blank, dotted, lowercase Register C
 * code (AMD-56-INV-03).</p>
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

    /** The default error code yielded by the no-code constructors (AMD-56-INV-03). */
    private static final String DEFAULT_ERROR_CODE = "integration.permanent_failure";
    private static final int SUGGESTED_HTTP_STATUS = 503;

    /**
     * A non-blank, dotted, lowercase Register C code: lowercase alphanumerics and
     * underscores in each dot-separated segment, with at least one dot
     * (e.g. {@code integration.auth_failed}).
     */
    private static final Pattern ERROR_CODE_PATTERN =
            Pattern.compile("^[a-z0-9_]+(\\.[a-z0-9_]+)+$");

    private final String errorCode;

    /**
     * Creates a new permanent integration exception with the given detail message
     * and the default error code {@code integration.permanent_failure}.
     *
     * @param message a user-readable description of the unrecoverable failure
     *                condition; should use Register C voice (direct, neutral)
     */
    public PermanentIntegrationException(String message) {
        super(message);
        this.errorCode = DEFAULT_ERROR_CODE;
    }

    /**
     * Creates a new permanent integration exception with the given detail message
     * and underlying cause, and the default error code
     * {@code integration.permanent_failure}.
     *
     * @param message a user-readable description of the unrecoverable failure
     *                condition; should use Register C voice (direct, neutral)
     * @param cause   the underlying cause of this failure
     */
    public PermanentIntegrationException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = DEFAULT_ERROR_CODE;
    }

    /**
     * Creates a new permanent integration exception carrying a specific
     * well-known error code (AMD-56).
     *
     * @param errorCode a non-blank, dotted, lowercase Register C code
     *                  (e.g. {@code integration.auth_failed})
     * @param message   a user-readable description of the unrecoverable failure
     *                  condition; should use Register C voice (direct, neutral)
     * @throws IllegalArgumentException if {@code errorCode} is not a dotted
     *                                  lowercase code
     */
    public PermanentIntegrationException(String errorCode, String message) {
        super(message);
        this.errorCode = validateErrorCode(errorCode);
    }

    /**
     * Creates a new permanent integration exception carrying a specific
     * well-known error code and an underlying cause (AMD-56).
     *
     * @param errorCode a non-blank, dotted, lowercase Register C code
     *                  (e.g. {@code integration.auth_failed})
     * @param message   a user-readable description of the unrecoverable failure
     *                  condition; should use Register C voice (direct, neutral)
     * @param cause     the underlying cause of this failure
     * @throws IllegalArgumentException if {@code errorCode} is not a dotted
     *                                  lowercase code
     */
    public PermanentIntegrationException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = validateErrorCode(errorCode);
    }

    @Override
    public String errorCode() {
        return errorCode;
    }

    @Override
    public int suggestedHttpStatus() {
        return SUGGESTED_HTTP_STATUS;
    }

    private static String validateErrorCode(String errorCode) {
        if (errorCode == null
                || errorCode.isBlank()
                || !ERROR_CODE_PATTERN.matcher(errorCode).matches()) {
            throw new IllegalArgumentException(
                    "errorCode must be a dotted lowercase code: " + errorCode);
        }
        return errorCode;
    }
}
