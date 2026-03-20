/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import java.util.Map;

/**
 * Response from an endpoint handler before HTTP serialization.
 *
 * <p>Endpoint handlers return this record to the REST API framework, which serializes
 * the {@link #body()} to JSON using the shared Jackson {@code ObjectMapper}
 * (LTD-08, {@code SNAKE_CASE} property naming) and writes the response to the HTTP
 * connection (Doc 09 §3.3, §8.2).</p>
 *
 * <p>The {@link #eTag()} and {@link #cacheControl()} fields intentionally duplicate
 * information present in the {@link #headers()} map. They are exposed as named fields
 * for middleware convenience — the ETag evaluation middleware (for {@code 304 Not Modified}
 * responses) accesses them directly rather than parsing the headers map. The values
 * in these fields and in the headers map must be consistent.</p>
 *
 * <p>The {@link #body()} is {@code null} for {@code 204 No Content} and
 * {@code 304 Not Modified} responses.</p>
 *
 * <p>Thread-safe (immutable record). The headers map is unmodifiable.</p>
 *
 * @param statusCode   the HTTP status code (e.g., 200, 202, 404)
 * @param headers      response headers including {@code Cache-Control}, {@code ETag},
 *                     {@code X-View-Position}, {@code X-Correlation-ID} (unmodifiable),
 *                     never {@code null}
 * @param body         response object to be serialized to JSON, {@code null} for
 *                     204/304 responses
 * @param eTag         the ETag value if applicable, {@code null} if no ETag;
 *                     also present in {@code headers} for serialization
 * @param cacheControl the Cache-Control directive if applicable, {@code null} if none;
 *                     also present in {@code headers} for serialization
 *
 * @see EndpointHandler
 * @see ApiRequest
 * @see ETagProvider
 * @see <a href="Doc 09 §3.3">Request Processing Pipeline</a>
 * @see <a href="Doc 09 §8.2">API Response Record</a>
 */
public record ApiResponse(
        int statusCode,
        Map<String, String> headers,
        Object body,
        String eTag,
        String cacheControl) {

    /**
     * Creates a new API response with unmodifiable headers.
     *
     * <p>The {@code headers} map is made unmodifiable via {@link Map#copyOf(Map)}.</p>
     *
     * @throws NullPointerException if {@code headers} is {@code null}
     */
    public ApiResponse {
        headers = Map.copyOf(headers);
    }
}
