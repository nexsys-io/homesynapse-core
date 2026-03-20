/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.ws;

/**
 * Lifecycle interface for the WebSocket API subsystem, consumed by the
 * Startup, Lifecycle and Shutdown module (Doc 12).
 *
 * <p>The {@link #start()} method is called during system initialization after
 * the Event Bus, REST API HTTP server, and State Store are ready (Doc 12,
 * Phase 5 startup sequence). It registers the WebSocket upgrade handler on
 * the shared HTTP server, starts the {@link EventRelay}, and begins accepting
 * connections.</p>
 *
 * <p>The {@link #stop()} method initiates graceful shutdown (Doc 12 §3.9,
 * shutdown step 3 — before REST API shutdown). It sends
 * {@link SubscriptionEndedMsg} with reason {@code "server_shutting_down"} to
 * all active subscriptions, drains connections for
 * {@code shutdown_drain_seconds} (default 5), closes all connections with
 * close code 1001 (Going Away), and stops the {@link EventRelay}.</p>
 *
 * <p>Thread-safe.</p>
 *
 * @see EventRelay
 * @see SubscriptionEndedMsg
 * @see <a href="Doc 10 §8.1">Service Interfaces</a>
 * @see <a href="Doc 12">Startup, Lifecycle and Shutdown</a>
 */
public interface WebSocketLifecycle {

    /**
     * Registers the WebSocket upgrade handler, starts the Event Relay,
     * and begins accepting client connections.
     *
     * <p>Called during Phase 5 of the startup sequence, after the Event Bus,
     * HTTP server, and State Store are operational.</p>
     */
    void start();

    /**
     * Initiates graceful shutdown of the WebSocket subsystem.
     *
     * <p>Sends termination notices to all active subscriptions, drains
     * in-flight messages, closes all connections with code 1001 (Going Away),
     * and stops the Event Relay.</p>
     */
    void stop();
}
