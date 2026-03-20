/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.ws;

import java.util.Objects;

/**
 * Client-to-server subscription request message (wire type: {@code "subscribe"}).
 *
 * <p>Creates a new subscription on the connection. Each connection may maintain
 * at most {@code max_subscriptions_per_connection} (default 10) concurrent
 * subscriptions. The server responds with {@link SubscriptionConfirmedMsg}
 * on success or {@link ErrorMsg} on failure.</p>
 *
 * <p>When {@code fromGlobalPosition} is present, the server replays events
 * from the EventStore starting at that position before switching to live
 * tailing (Doc 01 §9, replay-to-live transition). When
 * {@code includeInitialState} is {@code true}, a {@link StateSnapshotMsg}
 * is delivered before event streaming begins.</p>
 *
 * <p>Thread-safe (immutable record).</p>
 *
 * @param id                  client-assigned correlation integer, never {@code null}
 * @param filter              the subscription filter specification, never {@code null}
 * @param fromGlobalPosition  resume from this event store position;
 *                            {@code null} for live-only streaming
 * @param includeInitialState deliver a state snapshot before events;
 *                            {@code null} defaults to {@code false}
 *
 * @see SubscriptionConfirmedMsg
 * @see WsSubscriptionFilter
 * @see StateSnapshotMsg
 * @see <a href="Doc 10 §3.4">Subscription Management</a>
 */
public record SubscribeMsg(
        Integer id,
        WsSubscriptionFilter filter,
        Long fromGlobalPosition,
        Boolean includeInitialState
) implements WsMessage {

    /**
     * Creates a new subscribe message with validation of required fields.
     *
     * @throws NullPointerException if {@code id} or {@code filter} is {@code null}
     */
    public SubscribeMsg {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(filter, "filter must not be null");
    }
}
