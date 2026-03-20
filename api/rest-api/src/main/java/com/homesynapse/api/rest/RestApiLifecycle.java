/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

/**
 * Lifecycle interface for the REST API, consumed by the Startup, Lifecycle and
 * Shutdown module (Doc 12) for startup sequencing and graceful shutdown.
 *
 * <p>The {@link #start()} method is called during system initialization — Phase 5
 * of the Doc 12 startup sequence — after the Configuration System, Event Bus,
 * State Store, and subsystem registries are ready. It configures authentication,
 * registers all routes, binds the HTTP server, and begins accepting requests.</p>
 *
 * <p>The {@link #stop()} method is called during shutdown step 4 (Doc 12 §3.9).
 * It stops accepting new connections, drains in-flight requests (configurable
 * timeout per §9), and unbinds the port.</p>
 *
 * <p>Thread-safe.</p>
 *
 * @see RestApiServer
 * @see <a href="Doc 09 §8.1">REST API Lifecycle</a>
 * @see <a href="Doc 12 §3.9">Shutdown Sequence</a>
 */
public interface RestApiLifecycle {

    /**
     * Configures authentication, registers all routes, binds the HTTP server,
     * and begins accepting requests.
     *
     * <p>Called during Phase 5 of the startup sequence. By this point, the
     * Configuration System, Event Bus, State Store, and all subsystem registries
     * are initialized and ready to serve queries.</p>
     */
    void start();

    /**
     * Initiates graceful shutdown of the REST API.
     *
     * <p>Stops accepting new connections, drains in-flight requests with a
     * configurable timeout (Doc 09 §9 {@code shutdown_drain_seconds: 10}),
     * and unbinds the port.</p>
     */
    void stop();
}
