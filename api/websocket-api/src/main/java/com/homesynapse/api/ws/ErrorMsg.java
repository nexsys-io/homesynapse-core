/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.ws;

import java.util.Objects;

/**
 * Server-to-client protocol error message (wire type: {@code "error"}).
 *
 * <p>Represents a protocol-level error in the WebSocket session. Non-fatal
 * errors allow the connection to continue (the client corrects and retries).
 * Fatal errors are followed by connection closure with an appropriate
 * {@link WsCloseCode}.</p>
 *
 * <p>Error types follow Doc 09's RFC 9457 pattern for consistency across the
 * API surface. Common error types include:</p>
 * <ul>
 *   <li>{@code "authentication-required"} — no authentication received</li>
 *   <li>{@code "forbidden"} — invalid API key</li>
 *   <li>{@code "rate-limited"} — ping or message rate exceeded</li>
 *   <li>{@code "invalid-parameters"} — malformed message or invalid filter</li>
 *   <li>{@code "subscription-limit-exceeded"} — too many subscriptions</li>
 *   <li>{@code "filter-too-broad"} — resolved subject set exceeds limit</li>
 *   <li>{@code "replay-queue-full"} — replay admission control rejection</li>
 * </ul>
 *
 * <p>Thread-safe (immutable record).</p>
 *
 * @param id        echoes the client message {@code id} if in response to a
 *                  client request; {@code null} for server-initiated errors
 * @param errorType RFC 9457 error type slug, never {@code null}
 * @param detail    human-readable error description, never {@code null}
 * @param fatal     if {@code true}, the server will close the connection after
 *                  sending this message
 *
 * @see WsCloseCode
 * @see com.homesynapse.api.rest.ProblemType
 * @see <a href="Doc 10 §3.3">Error Handling</a>
 * @see <a href="Doc 10 §6.6">Malformed Message Escalation</a>
 */
public record ErrorMsg(
        Integer id,
        String errorType,
        String detail,
        boolean fatal
) implements WsMessage {

    /**
     * Creates a new error message with validation of required fields.
     *
     * @throws NullPointerException if {@code errorType} or {@code detail}
     *                              is {@code null}
     */
    public ErrorMsg {
        Objects.requireNonNull(errorType, "errorType must not be null");
        Objects.requireNonNull(detail, "detail must not be null");
    }
}
