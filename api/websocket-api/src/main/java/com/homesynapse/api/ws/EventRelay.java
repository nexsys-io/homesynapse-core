/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.ws;

/**
 * Single Event Bus subscriber that distributes events to connected
 * WebSocket clients.
 *
 * <p>The Event Relay reads each event once from the Event Bus and distributes
 * it in-memory to all matching client subscriptions (Doc 10 §3.6). This
 * avoids N redundant EventStore reads for N connected clients. The relay
 * maintains one bus subscription with a broad filter (all event types, all
 * priorities).</p>
 *
 * <p>For each event batch received, the relay evaluates it against every
 * active client subscription's materialized filter. Matching events are
 * serialized once per unique filter match and enqueued in each matching
 * client's send buffer. The relay advances its checkpoint after all matched
 * events have been enqueued (not after clients consume them).</p>
 *
 * <p>When a client subscribes with {@code fromGlobalPosition} behind the
 * relay's current checkpoint, the relay reads historical events from the
 * EventStore on the client's virtual thread.</p>
 *
 * <p>Thread-safe.</p>
 *
 * @see ClientConnection
 * @see WsSubscription
 * @see WebSocketLifecycle
 * @see <a href="Doc 10 §3.6">Event Relay Architecture</a>
 */
public interface EventRelay {

    /**
     * Registers as a single Event Bus subscriber and begins distributing
     * events to connected clients.
     */
    void start();

    /**
     * Unregisters from the Event Bus and stops event distribution.
     */
    void stop();

    /**
     * Registers a client connection for event distribution.
     *
     * @param client the client connection to add, never {@code null}
     */
    void addClient(ClientConnection client);

    /**
     * Unregisters a client connection from event distribution.
     *
     * @param connectionId the connection to remove, never {@code null}
     */
    void removeClient(String connectionId);

    /**
     * Returns the relay's current checkpoint position in the event log.
     *
     * @return the last processed {@code global_position}
     */
    long currentPosition();

    /**
     * Returns the number of currently registered client connections.
     *
     * @return the connected client count, non-negative
     */
    int connectedClientCount();
}
