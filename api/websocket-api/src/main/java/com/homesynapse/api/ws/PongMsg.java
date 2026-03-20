/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.ws;

import java.util.Objects;

/**
 * Server-to-client keepalive pong response message (wire type: {@code "pong"}).
 *
 * <p>Sent in response to {@link PingMsg}. Provides the server's current time
 * for client-side clock synchronization. This is an application-level pong
 * (JSON message), distinct from WebSocket protocol-level pong frames.</p>
 *
 * <p>Thread-safe (immutable record).</p>
 *
 * @param id         echoes the client's {@link PingMsg#id()},
 *                   never {@code null}
 * @param serverTime ISO 8601 timestamp of the server's current time,
 *                   never {@code null}
 *
 * @see PingMsg
 * @see <a href="Doc 10 §3.8">Keepalive</a>
 */
public record PongMsg(Integer id, String serverTime) implements WsMessage {

    /**
     * Creates a new pong message with validation of required fields.
     *
     * @throws NullPointerException if {@code id} or {@code serverTime}
     *                              is {@code null}
     */
    public PongMsg {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(serverTime, "serverTime must not be null");
    }
}
