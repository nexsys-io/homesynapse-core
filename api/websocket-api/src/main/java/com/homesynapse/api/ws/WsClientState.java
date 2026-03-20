/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.ws;

import com.homesynapse.api.rest.ApiKeyIdentity;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Per-connection state snapshot for a WebSocket client.
 *
 * <p>Captures the authenticated identity, active subscriptions, send buffer
 * metrics, and malformed message count at a point in time. The
 * {@link #apiKeyIdentity()} is obtained from the REST API's
 * {@link com.homesynapse.api.rest.AuthMiddleware} — WebSocket and REST share
 * the same authentication infrastructure (Doc 10 §3.5).</p>
 *
 * <p>Fields that are inherently mutable state (e.g., {@code bufferBytes},
 * {@code malformedMessageCount}) are captured as point-in-time snapshots.
 * Phase 3 may use a mutable class internally, but the public type signature
 * is a record for consistency with the rest of the codebase.</p>
 *
 * <p>Thread-safe (immutable record with unmodifiable map).</p>
 *
 * @param connectionId          connection identifier in {@code "ws_" + ULID}
 *                              format, never {@code null}
 * @param apiKeyIdentity        the authenticated caller identity from the REST
 *                              API's authentication infrastructure,
 *                              never {@code null}
 * @param authenticatedAt       timestamp of successful authentication,
 *                              never {@code null}
 * @param activeSubscriptions   active subscriptions keyed by subscriptionId,
 *                              never {@code null}, unmodifiable
 * @param bufferBytes           current send buffer size in bytes for
 *                              backpressure tracking
 * @param deliveryMode          connection-level delivery mode,
 *                              never {@code null}
 * @param malformedMessageCount consecutive malformed messages for
 *                              escalation tracking (Doc 10 §6.6)
 *
 * @see ApiKeyIdentity
 * @see WsSubscription
 * @see ClientConnection
 * @see <a href="Doc 10 §8.2">Client State</a>
 * @see <a href="Doc 10 §3.10">Per-Connection Rate Limiting</a>
 */
public record WsClientState(
        String connectionId,
        ApiKeyIdentity apiKeyIdentity,
        Instant authenticatedAt,
        Map<String, WsSubscription> activeSubscriptions,
        long bufferBytes,
        DeliveryMode deliveryMode,
        int malformedMessageCount
) {

    /**
     * Creates a new client state snapshot with validation and defensive copy.
     *
     * @throws NullPointerException if {@code connectionId},
     *                              {@code apiKeyIdentity},
     *                              {@code authenticatedAt},
     *                              {@code activeSubscriptions},
     *                              or {@code deliveryMode} is {@code null}
     */
    public WsClientState {
        Objects.requireNonNull(connectionId, "connectionId must not be null");
        Objects.requireNonNull(apiKeyIdentity, "apiKeyIdentity must not be null");
        Objects.requireNonNull(authenticatedAt, "authenticatedAt must not be null");
        Objects.requireNonNull(activeSubscriptions, "activeSubscriptions must not be null");
        activeSubscriptions = Map.copyOf(activeSubscriptions);
        Objects.requireNonNull(deliveryMode, "deliveryMode must not be null");
    }
}
