/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

/**
 * Narrow request/response SPI used by the M3.6e.2 endpoint handlers.
 *
 * <p>Mirrors the subset of {@link io.javalin.http.Context} actually consumed
 * by the handler classes ({@code pathParam}, {@code queryParam},
 * {@code status}, {@code header}, {@code json}). Exists so unit tests can
 * drive the handlers through a recording stub rather than instantiating
 * Javalin's thick {@code Context} (which has roughly eighty methods and
 * implementation-internal initialisation).</p>
 *
 * <p>Production code passes a {@link JavalinEndpointContext} adapter that
 * forwards each call to the underlying {@link io.javalin.http.Context}.</p>
 *
 * <p>Package-private — this is an internal seam, not part of the rest-api
 * module's exported API. The same package-private discipline applies that
 * keeps {@link ReadinessFilter.Responder} from leaking
 * {@code io.javalin.http} into the module's public surface (DEC-M3-16,
 * {@code -Xlint:exports}).</p>
 *
 * @see JavalinEndpointContext
 * @see ReadinessFilter.Responder
 */
interface EndpointContext {

    /**
     * Returns the value of a path parameter, e.g. {@code {entityId}} in the
     * route {@code /api/v1/entities/{entityId}}.
     *
     * @param name the parameter name as declared in the route pattern
     * @return the path parameter value, never {@code null} on a well-formed
     *         request (Javalin guarantees presence for declared path params)
     */
    String pathParam(String name);

    /**
     * Returns the value of a query parameter, or {@code null} if absent.
     *
     * @param name the query parameter name
     * @return the parameter value, or {@code null} when the parameter was
     *         not supplied by the client
     */
    String queryParam(String name);

    /**
     * Sets the HTTP response status code.
     *
     * @param code an HTTP status code (e.g., 200, 400, 404)
     */
    void status(int code);

    /**
     * Sets an HTTP response header.
     *
     * @param name  the header name (e.g., {@code X-HomeSynapse-View-Position})
     * @param value the header value
     */
    void header(String name, String value);

    /**
     * Writes the JSON response body. The underlying engine (Javalin's
     * built-in Jackson) is responsible for serialisation per LTD-08.
     *
     * @param body the body object to serialise as JSON
     */
    void json(Object body);
}
