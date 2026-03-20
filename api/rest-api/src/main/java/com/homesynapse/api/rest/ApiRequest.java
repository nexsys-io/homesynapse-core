/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Parsed and authenticated HTTP request presented to endpoint handlers.
 *
 * <p>By the time an {@link EndpointHandler} receives this record, all pre-processing
 * is complete (Doc 09 §3.3, §8.2):</p>
 * <ol>
 *   <li>Authentication — the {@link #identity()} is validated and non-null
 *       (INV-SE-02).</li>
 *   <li>Rate limiting — the caller's token bucket has been checked.</li>
 *   <li>Parameter parsing — path parameters and query parameters are extracted
 *       and available as typed maps.</li>
 *   <li>Correlation — the {@link #correlationId()} is either propagated from the
 *       {@code X-Correlation-ID} request header or generated as a ULID (§3.11).</li>
 * </ol>
 *
 * <p>The {@link #body()} field is typed as {@link Object} rather than
 * {@code com.fasterxml.jackson.databind.JsonNode} to avoid leaking a Jackson
 * dependency into the module's public API signatures. Phase 3 endpoint handlers
 * cast to {@code JsonNode} as documented — the runtime type is always a
 * {@code JsonNode} instance when the body is present.</p>
 *
 * <p>Thread-safe (immutable record). All maps are unmodifiable.</p>
 *
 * @param method        the HTTP method (e.g., {@code "GET"}, {@code "POST"}),
 *                      never {@code null}
 * @param pathPattern   the route pattern (e.g., {@code "/api/v1/entities/{entity_id}"}),
 *                      never {@code null}
 * @param pathParams    resolved path parameters (unmodifiable), never {@code null}
 * @param queryParams   query parameters supporting repeatable keys (unmodifiable),
 *                      never {@code null}
 * @param body          parsed request body; Phase 3 delivers a Jackson {@code JsonNode}
 *                      instance; {@code null} for requests without a body
 * @param identity      the authenticated caller identity, never {@code null}
 * @param correlationId request-scoped correlation ID for tracing (§3.11),
 *                      never {@code null}
 *
 * @see EndpointHandler
 * @see ApiKeyIdentity
 * @see ApiResponse
 * @see <a href="Doc 09 §3.3">Request Processing Pipeline</a>
 * @see <a href="Doc 09 §8.2">API Request Record</a>
 */
public record ApiRequest(
        String method,
        String pathPattern,
        Map<String, String> pathParams,
        Map<String, List<String>> queryParams,
        Object body,
        ApiKeyIdentity identity,
        String correlationId) {

    /**
     * Creates a new API request with validation of required fields.
     *
     * <p>Maps are made unmodifiable via {@link Map#copyOf(Map)}.</p>
     *
     * @throws NullPointerException if {@code method}, {@code pathPattern},
     *                              {@code pathParams}, {@code queryParams},
     *                              {@code identity}, or {@code correlationId}
     *                              is {@code null}
     */
    public ApiRequest {
        Objects.requireNonNull(method, "method must not be null");
        Objects.requireNonNull(pathPattern, "pathPattern must not be null");
        Objects.requireNonNull(pathParams, "pathParams must not be null");
        Objects.requireNonNull(queryParams, "queryParams must not be null");
        Objects.requireNonNull(identity, "identity must not be null");
        Objects.requireNonNull(correlationId, "correlationId must not be null");
        pathParams = Map.copyOf(pathParams);
        queryParams = Map.copyOf(queryParams);
    }
}
