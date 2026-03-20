/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.ws;

/**
 * Per-connection abstraction for send buffer management, backpressure
 * tracking, and connection lifecycle.
 *
 * <p>The {@link #send(WsMessage)} method enqueues messages for delivery —
 * it does not block on client consumption. If the send buffer exceeds the
 * hard ceiling ({@code hard_ceiling_kb}, default 128 KB), the connection
 * is closed with {@link WsCloseCode#CLIENT_TOO_SLOW}.</p>
 *
 * <p>Thread-safe. Multiple virtual threads may call methods on the same
 * connection concurrently (e.g., the Event Relay delivering events while
 * the handler processes client messages).</p>
 *
 * @see WsClientState
 * @see WsCloseCode
 * @see EventRelay
 * @see <a href="Doc 10 §8.1">Service Interfaces</a>
 */
public interface ClientConnection {

    /**
     * Returns this connection's identifier in {@code "ws_" + ULID} format.
     *
     * @return the connection identifier, never {@code null}
     */
    String connectionId();

    /**
     * Returns a point-in-time snapshot of this connection's state.
     *
     * @return the current client state, never {@code null}
     */
    WsClientState state();

    /**
     * Enqueues a message for delivery to the connected client.
     *
     * <p>The message is added to the send buffer. Phase 3 serializes via
     * {@link MessageCodec} and writes to the WebSocket. If the buffer
     * exceeds the hard ceiling, the connection is closed with
     * {@link WsCloseCode#CLIENT_TOO_SLOW}.</p>
     *
     * @param message the message to send, never {@code null}
     */
    void send(WsMessage message);

    /**
     * Closes the connection with the specified close code after sending
     * a final error message if appropriate.
     *
     * @param closeCode the application-specific close code, never {@code null}
     * @param reason    human-readable close reason, never {@code null}
     */
    void close(WsCloseCode closeCode, String reason);

    /**
     * Returns whether authentication has completed successfully on this
     * connection.
     *
     * @return {@code true} if the client has authenticated
     */
    boolean isAuthenticated();

    /**
     * Returns whether the underlying WebSocket connection is still open.
     *
     * @return {@code true} if the connection is open
     */
    boolean isOpen();
}
