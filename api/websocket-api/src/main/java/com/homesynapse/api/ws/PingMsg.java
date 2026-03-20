/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.ws;

import java.util.Objects;

/**
 * Client-to-server keepalive ping message (wire type: {@code "ping"}).
 *
 * <p>The server responds with {@link PongMsg} containing the server's
 * current time for client-side clock synchronization. This is an
 * application-level ping (JSON message), distinct from the WebSocket
 * protocol-level ping/pong frames that the server sends for connection
 * liveness detection.</p>
 *
 * <p>Rate-limited to {@code ping_limit} messages per second (default 2).
 * Exceeding the rate limit results in an {@link ErrorMsg} with type
 * {@code "rate-limited"}.</p>
 *
 * <p>Thread-safe (immutable record).</p>
 *
 * @param id client-assigned correlation integer, never {@code null}
 *
 * @see PongMsg
 * @see <a href="Doc 10 §3.8">Keepalive</a>
 */
public record PingMsg(Integer id) implements WsMessage {

    /**
     * Creates a new ping message with validation of the required field.
     *
     * @throws NullPointerException if {@code id} is {@code null}
     */
    public PingMsg {
        Objects.requireNonNull(id, "id must not be null");
    }
}
