/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import java.util.Objects;

/**
 * Per-field validation error included in RFC 9457 Problem Detail responses.
 *
 * <p>Field errors appear in the {@code errors} extension array of {@link ProblemDetail}
 * responses for {@code 400 Bad Request} and {@code 422 Unprocessable Entity} status
 * codes (Doc 09 §3.8). Each entry identifies a specific field that failed validation
 * and provides a human-readable explanation.</p>
 *
 * <p>The {@link #field()} value uses JSON path notation (e.g., {@code "parameters.level"})
 * to identify the offending field within the request body or query parameters.</p>
 *
 * <p>Thread-safe (immutable record).</p>
 *
 * @param field   the JSON path of the invalid field (e.g., {@code "parameters.level"}),
 *                never {@code null}
 * @param message a human-readable description of the validation failure,
 *                never {@code null}
 *
 * @see ProblemDetail
 * @see ProblemType#INVALID_PARAMETERS
 * @see ProblemType#INVALID_COMMAND
 * @see <a href="Doc 09 §3.8">Error Response Model</a>
 */
public record FieldError(String field, String message) {

    /**
     * Creates a new field error with validation of required fields.
     *
     * @throws NullPointerException if {@code field} or {@code message} is {@code null}
     */
    public FieldError {
        Objects.requireNonNull(field, "field must not be null");
        Objects.requireNonNull(message, "message must not be null");
    }
}
