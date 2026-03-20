/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import java.util.List;
import java.util.Objects;

/**
 * Immutable RFC 9457 Problem Details representation for structured API error responses.
 *
 * <p>Every error response from the HomeSynapse REST API is serialized as an
 * {@code application/problem+json} document conforming to RFC 9457 (Doc 09 §3.8).
 * This record carries the full set of fields required for that representation.</p>
 *
 * <p>The {@link #type()} field provides the machine-readable error identifier as a
 * {@link ProblemType} enum value. The {@link ProblemType#typeUri()} method produces
 * the full URI for JSON serialization. The {@link #correlationId()} links the error
 * response to server-side structured log entries for diagnosis (AMD-15, §3.11).</p>
 *
 * <p>The {@link #errors()} list is present only for validation errors ({@code 400}
 * and {@code 422} status codes). For all other error types, {@code errors} is
 * {@code null} — not an empty list. This follows the RFC 9457 convention that
 * extension members are omitted when not applicable.</p>
 *
 * <p>Thread-safe (immutable record).</p>
 *
 * @param type          the machine-readable problem type identifier, never {@code null}
 * @param title         a short, human-readable summary of the problem, never {@code null}
 * @param status        the HTTP status code for this error response
 * @param detail        a human-readable explanation specific to this occurrence,
 *                      never {@code null}
 * @param instance      the request path that generated this error, {@code null} if
 *                      not available
 * @param correlationId the request-scoped correlation ID linking to structured log
 *                      entries (AMD-15), never {@code null}
 * @param errors        per-field validation errors for 400/422 responses, {@code null}
 *                      for non-validation errors; when present, the list is unmodifiable
 *
 * @see ProblemType
 * @see FieldError
 * @see ApiException
 * @see ProblemDetailMapper
 * @see <a href="Doc 09 §3.8">Error Response Model</a>
 */
public record ProblemDetail(
        ProblemType type,
        String title,
        int status,
        String detail,
        String instance,
        String correlationId,
        List<FieldError> errors) {

    /**
     * Creates a new Problem Detail with validation of required fields.
     *
     * <p>The {@code errors} list, if non-null, is made unmodifiable via
     * {@link List#copyOf(java.util.Collection)}.</p>
     *
     * @throws NullPointerException if {@code type}, {@code title}, {@code detail},
     *                              or {@code correlationId} is {@code null}
     */
    public ProblemDetail {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(detail, "detail must not be null");
        Objects.requireNonNull(correlationId, "correlationId must not be null");
        if (errors != null) {
            errors = List.copyOf(errors);
        }
    }
}
