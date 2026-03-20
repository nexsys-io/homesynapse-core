/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.ws;

import com.homesynapse.api.rest.ApiException;

import java.util.List;

/**
 * Manages subscription lifecycle on a WebSocket connection.
 *
 * <p>Filter resolution ({@code areaRefs}, {@code labelRefs},
 * {@code entityTypes}, {@code capabilities} → materialized subject ref set)
 * is performed at subscription creation time and cached (Glossary §1.5,
 * label resolution determinism). The resolved set does not update
 * dynamically.</p>
 *
 * <p>Subscription limit: {@code max_subscriptions_per_connection} (default 10).
 * Exceeding the limit results in an {@link ApiException} or an
 * {@link ErrorMsg} with type {@code "subscription-limit-exceeded"}. If the
 * resolved filter subject set exceeds {@code max_resolved_subjects} (default
 * 500), the subscription is rejected with {@code "filter-too-broad"}.</p>
 *
 * <p>Thread-safe.</p>
 *
 * @see WsSubscription
 * @see WsSubscriptionFilter
 * @see ClientConnection
 * @see <a href="Doc 10 §8.1">Service Interfaces</a>
 */
public interface SubscriptionManager {

    /**
     * Creates a new subscription on the specified connection.
     *
     * <p>Resolves the filter to a materialized subject ref set, enforces the
     * subscription limit, and initiates replay if
     * {@link SubscribeMsg#fromGlobalPosition()} is present.</p>
     *
     * @param connectionId the connection to create the subscription on,
     *                     never {@code null}
     * @param request      the subscribe message from the client,
     *                     never {@code null}
     * @return the created subscription state, never {@code null}
     * @throws ApiException if the filter is invalid, the subscription limit
     *                      is exceeded, or the filter resolves to too many
     *                      subjects
     */
    WsSubscription subscribe(String connectionId, SubscribeMsg request) throws ApiException;

    /**
     * Removes an active subscription from the specified connection.
     *
     * @param connectionId   the connection owning the subscription,
     *                       never {@code null}
     * @param subscriptionId the subscription to remove, never {@code null}
     * @throws ApiException if the subscription does not exist on the
     *                      specified connection
     */
    void unsubscribe(String connectionId, String subscriptionId) throws ApiException;

    /**
     * Lists all active subscriptions for a connection.
     *
     * @param connectionId the connection to query, never {@code null}
     * @return unmodifiable list of active subscriptions, never {@code null}
     */
    List<WsSubscription> subscriptions(String connectionId);

    /**
     * Removes all subscriptions for a connection. Called on disconnect.
     *
     * @param connectionId the connection to clean up, never {@code null}
     */
    void removeAll(String connectionId);
}
