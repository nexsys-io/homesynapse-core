/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.ws;

import java.util.Objects;

/**
 * Server-to-client subscription termination message
 * (wire type: {@code "subscription_ended"}).
 *
 * <p>Indicates that the server has terminated a subscription. The client
 * should not expect further events for this {@code subscriptionId}.</p>
 *
 * <p>Common reasons:</p>
 * <ul>
 *   <li>{@code "server_shutting_down"} — graceful shutdown in progress;
 *       the client should reconnect with exponential backoff (initial 1s,
 *       max 30s, jitter ±500ms per Doc 10 §3.8)</li>
 *   <li>{@code "replay_limit_exceeded"} — the replay range exceeded the
 *       maximum; {@code lastGlobalPosition} indicates where delivery stopped
 *       so the client can use
 *       {@code GET /api/v1/events?after_position={lastGlobalPosition}} to
 *       fill the gap via REST before resubscribing</li>
 *   <li>{@code "subscription_removed"} — the subscription was removed by
 *       server-side policy</li>
 * </ul>
 *
 * <p>Thread-safe (immutable record).</p>
 *
 * @param id                  {@code null} (server-initiated message)
 * @param subscriptionId      the terminated subscription, never {@code null}
 * @param reason              termination reason string, never {@code null}
 * @param lastGlobalPosition  the {@code global_position} where event delivery
 *                            stopped; {@code null} unless {@code reason} is
 *                            {@code "replay_limit_exceeded"}
 *
 * @see WebSocketLifecycle#stop()
 * @see <a href="Doc 10 §3.4">Subscription Management</a>
 * @see <a href="Doc 10 §3.9">Reconnection</a>
 */
public record SubscriptionEndedMsg(
        Integer id,
        String subscriptionId,
        String reason,
        Long lastGlobalPosition
) implements WsMessage {

    /**
     * Creates a new subscription ended message with validation of required fields.
     *
     * @throws NullPointerException if {@code subscriptionId} or {@code reason}
     *                              is {@code null}
     */
    public SubscriptionEndedMsg {
        Objects.requireNonNull(subscriptionId, "subscriptionId must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
    }
}
