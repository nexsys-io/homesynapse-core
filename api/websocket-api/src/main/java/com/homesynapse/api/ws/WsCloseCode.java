/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.ws;

/**
 * Application-specific WebSocket close codes used by HomeSynapse.
 *
 * <p>These codes occupy the 4000–4999 range reserved for application use
 * by RFC 6455 §7.4.2. Each code aligns with an HTTP status code for
 * diagnostic consistency (e.g., 4403 ↔ 403 Forbidden).</p>
 *
 * <p>When the server closes a connection, it sends a close frame containing
 * one of these codes along with a human-readable reason string. The client
 * uses the code to determine appropriate recovery behavior.</p>
 *
 * @see ClientConnection#close(WsCloseCode, String)
 * @see <a href="Doc 10 §3.5">Authentication</a>
 * @see <a href="Doc 10 §3.7">Backpressure</a>
 * @see <a href="RFC 6455 §7.4.2">WebSocket Close Code Ranges</a>
 */
public enum WsCloseCode {

    /**
     * Invalid API key provided in the {@link AuthenticateMsg}. Aligned with
     * HTTP 403 Forbidden. The client should prompt the user for a valid key
     * before reconnecting.
     */
    AUTH_FAILED(4403),

    /**
     * No {@link AuthenticateMsg} received within the authentication timeout
     * (default 5 seconds). Aligned with HTTP 408 Request Timeout. The client
     * should reconnect and send the authentication message promptly.
     */
    AUTH_TIMEOUT(4408),

    /**
     * The client's send buffer exceeded the hard ceiling
     * ({@code hard_ceiling_kb}, default 128 KB). Aligned with HTTP 429
     * Too Many Requests. The client is consuming events too slowly. The
     * client should reconnect with a narrower subscription filter or
     * increase its consumption rate.
     */
    CLIENT_TOO_SLOW(4429),

    /**
     * The client attempted to exceed the maximum number of concurrent
     * subscriptions per connection ({@code max_subscriptions_per_connection},
     * default 10). Aligned with HTTP 409 Conflict. The client should
     * unsubscribe from an existing subscription before creating a new one.
     */
    SUBSCRIPTION_LIMIT(4409),

    /**
     * The client sent too many consecutive malformed messages, exceeding
     * the threshold defined in the server configuration (Doc 10 §6.6).
     * Aligned with HTTP 400 Bad Request. The client should verify its
     * message serialization logic before reconnecting.
     */
    MALFORMED_MESSAGES(4400);

    private final int code;

    WsCloseCode(int code) {
        this.code = code;
    }

    /**
     * Returns the numeric WebSocket close code in the 4000–4999 application range.
     *
     * @return the close code, never negative
     */
    public int code() {
        return code;
    }
}
