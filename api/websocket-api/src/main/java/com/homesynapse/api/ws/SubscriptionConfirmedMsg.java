/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.ws;

import java.util.Objects;

/**
 * Server-to-client subscription confirmation message
 * (wire type: {@code "subscription_confirmed"}).
 *
 * <p>Confirms successful subscription creation in response to
 * {@link SubscribeMsg}. The {@code subscriptionId} is used for subsequent
 * {@link UnsubscribeMsg} requests and appears on all {@link EventsMsg}
 * deliveries for this subscription.</p>
 *
 * <p>The {@code filter} is echoed back to confirm what filter was registered.
 * Phase 3 may enrich the echo with resolved metadata (e.g., resolved
 * subject count).</p>
 *
 * <p>Thread-safe (immutable record).</p>
 *
 * @param id             echoes the client's {@link SubscribeMsg#id()},
 *                       never {@code null}
 * @param subscriptionId server-assigned subscription identifier in
 *                       {@code "sub_" + ULID} format, never {@code null}
 * @param filter         the subscription filter as registered,
 *                       never {@code null}
 * @param replayFrom     the {@code from_global_position} if the subscription
 *                       requested replay; {@code null} for live-only subscriptions
 *
 * @see SubscribeMsg
 * @see UnsubscribeMsg
 * @see EventsMsg
 * @see <a href="Doc 10 §3.4">Subscription Management</a>
 */
public record SubscriptionConfirmedMsg(
        Integer id,
        String subscriptionId,
        WsSubscriptionFilter filter,
        Long replayFrom
) implements WsMessage {

    /**
     * Creates a new subscription confirmed message with validation of required fields.
     *
     * @throws NullPointerException if {@code id}, {@code subscriptionId},
     *                              or {@code filter} is {@code null}
     */
    public SubscriptionConfirmedMsg {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(subscriptionId, "subscriptionId must not be null");
        Objects.requireNonNull(filter, "filter must not be null");
    }
}
