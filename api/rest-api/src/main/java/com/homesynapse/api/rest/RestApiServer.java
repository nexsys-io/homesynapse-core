/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

/**
 * Abstract HTTP server operations that isolate the HTTP server implementation choice.
 *
 * <p>Doc 09 §3.9 evaluates three implementation options: JDK {@code HttpServer},
 * Javalin 6.x, and Eclipse Jetty 12. Phase 2 defines this interface; Phase 3
 * implements it against the chosen library. No Javalin, Jetty, or
 * {@code com.sun.net.httpserver} types appear in any Phase 2 type signature.</p>
 *
 * <p>Route path patterns use {@code {param}} syntax for path parameters
 * (e.g., {@code /api/v1/entities/{entity_id}}). The implementation extracts
 * path parameter values and populates {@link ApiRequest#pathParams()}.</p>
 *
 * <p>The implementation dispatches each request on a virtual thread (LTD-01).
 * Connection handling, request parsing, and response serialization are managed
 * by the underlying HTTP server library.</p>
 *
 * <p>Thread-safe.</p>
 *
 * @see EndpointHandler
 * @see RestApiLifecycle
 * @see <a href="Doc 09 §3.9">HTTP Server Selection</a>
 * @see <a href="Doc 09 §8.1">Server Interface</a>
 */
public interface RestApiServer {

    /**
     * Registers an endpoint handler for a method and path pattern combination.
     *
     * <p>Path patterns use {@code {param}} syntax for path parameters
     * (e.g., {@code /api/v1/entities/{entity_id}}). Each method + path pattern
     * combination may have at most one registered handler.</p>
     *
     * @param method      the HTTP method (e.g., {@code "GET"}, {@code "POST"}),
     *                    never {@code null}
     * @param pathPattern the route pattern (e.g., {@code "/api/v1/entities/{entity_id}"}),
     *                    never {@code null}
     * @param handler     the handler to invoke for matching requests,
     *                    never {@code null}
     */
    void registerRoute(String method, String pathPattern, EndpointHandler handler);

    /**
     * Binds and starts the HTTP server on the specified host and port.
     *
     * <p>After this method returns, the server is accepting connections.
     * Each incoming request is dispatched on a virtual thread.</p>
     *
     * @param host the hostname or IP address to bind to (e.g., {@code "0.0.0.0"}),
     *             never {@code null}
     * @param port the port to bind to; use {@code 0} for a system-assigned port
     */
    void start(String host, int port);

    /**
     * Initiates graceful shutdown with connection drain.
     *
     * <p>Stops accepting new connections and waits up to {@code drainSeconds}
     * for in-flight requests to complete before forcibly closing remaining
     * connections (Doc 09 §9 {@code shutdown_drain_seconds: 10}).</p>
     *
     * @param drainSeconds maximum seconds to wait for in-flight requests to complete
     */
    void stop(int drainSeconds);

    /**
     * Returns whether the HTTP server is currently running and accepting connections.
     *
     * @return {@code true} if the server is running, {@code false} otherwise
     */
    boolean isRunning();

    /**
     * Returns the port the server is bound to.
     *
     * <p>May differ from the configured port if {@code 0} was used for
     * system-assigned port selection.</p>
     *
     * @return the bound port number
     */
    int port();
}
