/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.ws;

import java.util.Objects;

/**
 * Active subscription state for a WebSocket connection.
 *
 * <p>Phase 2 captures the data contract for an active subscription. Phase 3
 * manages the mutable lifecycle including delivery mode transitions, replay
 * cursor advancement, and coalescing timer state.</p>
 *
 * <p>The {@code subscriptionId} follows the {@code "sub_" + ULID} format,
 * providing human-readable disambiguation in log entries (Doc 10 §8.2).
 * These are subsystem-internal operational identifiers, not typed ULID
 * wrappers registered in the Identity and Addressing Model.</p>
 *
 * <p>Thread-safe (immutable record).</p>
 *
 * @param subscriptionId server-assigned identifier in {@code "sub_" + ULID}
 *                       format, never {@code null}
 * @param connectionId   the owning connection in {@code "ws_" + ULID} format,
 *                       never {@code null}
 * @param filter         the client-specified subscription filter,
 *                       never {@code null}
 * @param deliveryMode   current delivery mode for this subscription,
 *                       never {@code null}
 * @param replayCursor   current position in replay; {@code null} if the
 *                       subscription is not replaying historical events
 * @param stateChangeOnly resolved from the filter's
 *                        {@link WsSubscriptionFilter#stateChangeOnly()} field
 * @param minIntervalMs  coalescing floor in milliseconds; {@code null} to
 *                       use server default
 * @param maxIntervalMs  coalescing ceiling in milliseconds; {@code null} to
 *                       use server default
 *
 * @see WsSubscriptionFilter
 * @see SubscriptionManager
 * @see WsClientState
 * @see <a href="Doc 10 §4.3">Subscription State</a>
 */
public record WsSubscription(
        String subscriptionId,
        String connectionId,
        WsSubscriptionFilter filter,
        DeliveryMode deliveryMode,
        Long replayCursor,
        boolean stateChangeOnly,
        Integer minIntervalMs,
        Integer maxIntervalMs
) {

    /**
     * Creates a new subscription state with validation of required fields.
     *
     * @throws NullPointerException if {@code subscriptionId},
     *                              {@code connectionId}, {@code filter},
     *                              or {@code deliveryMode} is {@code null}
     */
    public WsSubscription {
        Objects.requireNonNull(subscriptionId, "subscriptionId must not be null");
        Objects.requireNonNull(connectionId, "connectionId must not be null");
        Objects.requireNonNull(filter, "filter must not be null");
        Objects.requireNonNull(deliveryMode, "deliveryMode must not be null");
    }
}
