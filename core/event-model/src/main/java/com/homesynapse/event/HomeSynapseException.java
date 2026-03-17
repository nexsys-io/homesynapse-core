/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

/**
 * Base exception for all typed, domain-level errors in HomeSynapse Core.
 *
 * <p>Every concrete subclass provides two pieces of structured metadata:</p>
 * <ul>
 *   <li>{@link #errorCode()} — a stable, dotted string (e.g., {@code "entity.not_found"})
 *       suitable for programmatic matching in API responses, log filtering, and
 *       automation condition evaluation.</li>
 *   <li>{@link #suggestedHttpStatus()} — the HTTP status code that the REST API layer
 *       should use when translating this exception into an error response.</li>
 * </ul>
 *
 * <p>This hierarchy is separate from {@link SequenceConflictException}, which predates
 * it and has different semantics — sequence conflicts are optimistic concurrency signals,
 * not domain errors.</p>
 *
 * <p>All subclasses are immutable and thread-safe. Error codes use dotted lowercase
 * notation with underscores for multi-word segments (e.g., {@code "config.validation_failed"}).</p>
 *
 * @see SequenceConflictException
 */
public abstract class HomeSynapseException extends Exception {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates a new exception with the given detail message.
     *
     * @param message the detail message; should describe the specific failure condition
     */
    protected HomeSynapseException(String message) {
        super(message);
    }

    /**
     * Creates a new exception with the given detail message and cause.
     *
     * @param message the detail message; should describe the specific failure condition
     * @param cause   the underlying cause of this exception
     */
    protected HomeSynapseException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Returns the stable, dotted error code for this exception type.
     *
     * <p>Error codes are lowercase dotted strings suitable for programmatic matching
     * (e.g., {@code "entity.not_found"}, {@code "capability.mismatch"}). They are
     * stable across releases and may be used in API responses, log filters, and
     * automation conditions.</p>
     *
     * @return the error code, never {@code null} or blank
     */
    public abstract String errorCode();

    /**
     * Returns the HTTP status code that the REST API should use when translating
     * this exception into an error response.
     *
     * <p>This is a <em>suggestion</em> — the API layer may override it based on
     * context (e.g., a 404 might become a 403 if the caller lacks permission to
     * know the resource exists).</p>
     *
     * @return the suggested HTTP status code (e.g., 404, 409, 422, 503)
     */
    public abstract int suggestedHttpStatus();
}
