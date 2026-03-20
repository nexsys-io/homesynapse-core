/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

/**
 * Functional interface for HTTP request handling.
 *
 * <p>Each REST API endpoint registers one {@code EndpointHandler} with the
 * {@link RestApiServer} (Doc 09 §8.1). Handlers receive a fully parsed,
 * authenticated {@link ApiRequest} and return an {@link ApiResponse} for
 * serialization.</p>
 *
 * <p><strong>Virtual thread execution:</strong> Handlers run on virtual threads
 * (LTD-01) — each request gets its own virtual thread, so blocking I/O
 * (e.g., SQLite reads via the EventStore) is acceptable without explicit async
 * handling. The virtual thread unmounts from its carrier thread during I/O waits.</p>
 *
 * <p><strong>Thread-safety:</strong> Handlers must be stateless or thread-safe,
 * as concurrent requests invoke the same handler instance.</p>
 *
 * @see ApiRequest
 * @see ApiResponse
 * @see RestApiServer#registerRoute(String, String, EndpointHandler)
 * @see <a href="Doc 09 §8.1">Endpoint Handler Interface</a>
 */
@FunctionalInterface
public interface EndpointHandler {

    /**
     * Handles an HTTP request and produces a response.
     *
     * <p>The request has been fully pre-processed: authentication is verified,
     * rate limits are checked, path and query parameters are parsed, and a
     * correlation ID is assigned. The handler focuses solely on business logic —
     * querying internal subsystem interfaces and assembling the response.</p>
     *
     * @param request the parsed and authenticated HTTP request, never {@code null}
     * @return the response to serialize and send to the client, never {@code null}
     * @throws ApiException for structured error responses (authentication failures,
     *                      parameter validation errors, rate limit exceeded, etc.)
     */
    ApiResponse handle(ApiRequest request) throws ApiException;
}
