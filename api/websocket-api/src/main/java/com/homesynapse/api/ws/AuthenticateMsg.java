/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.ws;

import java.util.Objects;

/**
 * Client-to-server authentication message (wire type: {@code "authenticate"}).
 *
 * <p>This MUST be the first message after WebSocket connection establishment.
 * The server validates the API key against the same key store used by the
 * REST API's {@link com.homesynapse.api.rest.AuthMiddleware}. On success,
 * the server responds with {@link AuthResultMsg} with {@code success: true}.
 * On failure, the server responds with {@link AuthResultMsg} with
 * {@code success: false} followed by connection closure with
 * {@link WsCloseCode#AUTH_FAILED}.</p>
 *
 * <p>If this message is not received within the authentication timeout
 * (default 5 seconds), the server closes the connection with
 * {@link WsCloseCode#AUTH_TIMEOUT}.</p>
 *
 * <p>The raw API key value is validated against bcrypt hashes — it is
 * NEVER stored or logged by the server.</p>
 *
 * <p>Thread-safe (immutable record).</p>
 *
 * @param id     client-assigned correlation integer, never {@code null}
 * @param apiKey the raw API key value for validation, never {@code null}
 *
 * @see AuthResultMsg
 * @see WsCloseCode#AUTH_FAILED
 * @see WsCloseCode#AUTH_TIMEOUT
 * @see <a href="Doc 10 §3.5">Authentication</a>
 */
public record AuthenticateMsg(Integer id, String apiKey) implements WsMessage {

    /**
     * Creates a new authentication message with validation of required fields.
     *
     * @throws NullPointerException if {@code id} or {@code apiKey} is {@code null}
     */
    public AuthenticateMsg {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(apiKey, "apiKey must not be null");
    }
}
