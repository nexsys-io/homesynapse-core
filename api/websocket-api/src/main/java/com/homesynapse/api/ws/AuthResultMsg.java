/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.ws;

import java.util.Objects;

/**
 * Server-to-client authentication result message (wire type: {@code "auth_result"}).
 *
 * <p>Sent in response to {@link AuthenticateMsg}. On success, provides the
 * server-assigned {@code connectionId} used for logging and debugging, and
 * the server's current time for clock synchronization. On failure, provides
 * an error type and detail; the server closes the connection with
 * {@link WsCloseCode#AUTH_FAILED} after sending this message.</p>
 *
 * <p>Thread-safe (immutable record).</p>
 *
 * @param id          echoes the client's {@link AuthenticateMsg#id()},
 *                    never {@code null}
 * @param success     {@code true} if authentication succeeded
 * @param connectionId server-assigned connection identifier in
 *                     {@code "ws_" + ULID} format; {@code null} when
 *                     {@code success} is {@code false}
 * @param serverTime  ISO 8601 timestamp of the server's current time;
 *                    {@code null} when {@code success} is {@code false}
 * @param errorType   RFC 9457 error type slug (e.g., {@code "forbidden"});
 *                    {@code null} when {@code success} is {@code true}
 * @param errorDetail human-readable error description; {@code null} when
 *                    {@code success} is {@code true}
 *
 * @see AuthenticateMsg
 * @see WsCloseCode#AUTH_FAILED
 * @see <a href="Doc 10 §3.5">Authentication</a>
 */
public record AuthResultMsg(
        Integer id,
        boolean success,
        String connectionId,
        String serverTime,
        String errorType,
        String errorDetail
) implements WsMessage {

    /**
     * Creates a new authentication result message.
     *
     * @throws NullPointerException if {@code id} is {@code null}
     */
    public AuthResultMsg {
        Objects.requireNonNull(id, "id must not be null");
    }
}
