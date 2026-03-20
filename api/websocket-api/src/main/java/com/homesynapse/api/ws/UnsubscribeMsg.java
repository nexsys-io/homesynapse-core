/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.ws;

import java.util.Objects;

/**
 * Client-to-server unsubscribe request message (wire type: {@code "unsubscribe"}).
 *
 * <p>Removes an active subscription from the connection. After removal, no
 * further events are delivered for that subscription. If the specified
 * {@code subscriptionId} does not exist on this connection, the server
 * responds with {@link ErrorMsg} (type {@code "invalid-parameters"},
 * {@code fatal: false}).</p>
 *
 * <p>Thread-safe (immutable record).</p>
 *
 * @param id             client-assigned correlation integer, never {@code null}
 * @param subscriptionId the {@code subscription_id} returned by
 *                       {@link SubscriptionConfirmedMsg}, never {@code null}
 *
 * @see SubscriptionConfirmedMsg
 * @see SubscriptionManager#unsubscribe(String, String)
 * @see <a href="Doc 10 §3.4">Subscription Management</a>
 */
public record UnsubscribeMsg(Integer id, String subscriptionId) implements WsMessage {

    /**
     * Creates a new unsubscribe message with validation of required fields.
     *
     * @throws NullPointerException if {@code id} or {@code subscriptionId}
     *                              is {@code null}
     */
    public UnsubscribeMsg {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(subscriptionId, "subscriptionId must not be null");
    }
}
