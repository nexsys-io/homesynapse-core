/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.ws;

import java.util.Objects;

/**
 * Server-to-client replay queue notification message
 * (wire type: {@code "replay_queued"}).
 *
 * <p>Sent when a subscription's replay request is queued during post-restart
 * admission control (Doc 10 §3.9). Replays are served sequentially — at most
 * {@code max_concurrent_replays} (default 1) active at a time.</p>
 *
 * <p>While queued, the client receives LIVE events normally — the subscription
 * is active for new events even before replay fills the gap. The client
 * deduplicates events that arrive via both live and replay streams using
 * {@code global_position}.</p>
 *
 * <p>Thread-safe (immutable record).</p>
 *
 * @param id                {@code null} (server-initiated message)
 * @param subscriptionId    the subscription whose replay is queued,
 *                          never {@code null}
 * @param positionInQueue   FIFO queue position (1-indexed)
 * @param estimatedWaitMs   best-effort wait estimate in milliseconds based
 *                          on average replay throughput
 * @param lastSeenPosition  echoes the client's requested
 *                          {@code fromGlobalPosition}, confirming the server
 *                          registered the replay starting point
 *
 * @see SubscribeMsg#fromGlobalPosition()
 * @see EventRelay
 * @see <a href="Doc 10 §3.9">Reconnection and Admission Control</a>
 */
public record ReplayQueuedMsg(
        Integer id,
        String subscriptionId,
        int positionInQueue,
        long estimatedWaitMs,
        long lastSeenPosition
) implements WsMessage {

    /**
     * Creates a new replay queued message with validation of required fields.
     *
     * @throws NullPointerException if {@code subscriptionId} is {@code null}
     */
    public ReplayQueuedMsg {
        Objects.requireNonNull(subscriptionId, "subscriptionId must not be null");
    }
}
