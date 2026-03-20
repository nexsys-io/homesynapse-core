/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.ws;

/**
 * Handles individual WebSocket connection events.
 *
 * <p>Each WebSocket connection runs on a virtual thread (LTD-01). The handler
 * is responsible for authentication timeout enforcement, message deserialization
 * (via {@link MessageCodec}), and dispatch to the appropriate processing logic.</p>
 *
 * <p>The {@link #onMessage(String, String)} callback receives raw JSON
 * ({@code String}), not a deserialized {@link WsMessage}. Deserialization is the
 * handler's responsibility (via {@link MessageCodec}) to keep this interface
 * transport-agnostic.</p>
 *
 * <p>Thread-safe. One handler instance is shared across all connections;
 * all methods must be safe for concurrent invocation from multiple virtual
 * threads.</p>
 *
 * @see MessageCodec
 * @see ClientConnection
 * @see WebSocketLifecycle
 * @see <a href="Doc 10 §8.1">Service Interfaces</a>
 */
public interface WebSocketHandler {

    /**
     * Called when a WebSocket connection is established, before authentication.
     *
     * <p>The connection is not yet authenticated at this point. The handler
     * should start the authentication timeout timer.</p>
     *
     * @param connectionId the connection identifier in {@code "ws_" + ULID}
     *                     format, never {@code null}
     */
    void onConnect(String connectionId);

    /**
     * Called when a text frame is received from the client.
     *
     * <p>The {@code message} parameter is the raw JSON text. The handler is
     * responsible for deserializing it to the appropriate {@link WsMessage}
     * subtype via {@link MessageCodec} and dispatching accordingly.</p>
     *
     * @param connectionId the connection identifier, never {@code null}
     * @param message      the raw JSON text frame content, never {@code null}
     */
    void onMessage(String connectionId, String message);

    /**
     * Called when the connection closes for any reason.
     *
     * <p>The handler should clean up all subscriptions for this connection
     * via {@link SubscriptionManager#removeAll(String)} and deregister from
     * the {@link EventRelay}.</p>
     *
     * @param connectionId the connection identifier, never {@code null}
     * @param closeCode    the WebSocket close code (RFC 6455 §7.4 or
     *                     application-specific 4000–4999 range)
     * @param reason       human-readable close reason, never {@code null}
     */
    void onClose(String connectionId, int closeCode, String reason);

    /**
     * Called on transport-level errors (e.g., I/O failure, protocol violation).
     *
     * <p>The handler should log the error and close the connection if it is
     * still open.</p>
     *
     * @param connectionId the connection identifier, never {@code null}
     * @param error        the transport error, never {@code null}
     */
    void onError(String connectionId, Throwable error);
}
