/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.ws;

import java.util.List;
import java.util.Objects;

/**
 * Server-to-client event delivery message (wire type: {@code "events"}).
 *
 * <p>Delivers one or more events matching the subscription's filter. The
 * {@code events} list contains serialized event envelope maps — not typed
 * {@code EventEnvelope} objects — to avoid leaking event-model types into
 * the wire protocol hierarchy. Phase 3 delivers Jackson {@code ObjectNode}
 * instances.</p>
 *
 * <p>Delivery behavior varies by {@link DeliveryMode}:</p>
 * <ul>
 *   <li>{@link DeliveryMode#NORMAL} — typically a single event per message</li>
 *   <li>{@link DeliveryMode#BATCHED} — multiple events accumulated within
 *       the batch window (Doc 10 §3.7, Stage 2)</li>
 *   <li>{@link DeliveryMode#COALESCED} — coalescable DIAGNOSTIC events may
 *       carry a {@code coalesced: true} flag indicating intermediate values
 *       were not delivered (Doc 10 §3.7, Stage 3)</li>
 * </ul>
 *
 * <p>All events within a message are ordered by {@code global_position}.</p>
 *
 * <p>Thread-safe (immutable record with unmodifiable list).</p>
 *
 * @param id             {@code null} (server-initiated message)
 * @param subscriptionId the subscription that matched these events,
 *                       never {@code null}
 * @param deliveryMode   the current delivery mode for this subscription,
 *                       never {@code null}
 * @param events         serialized event envelope objects ordered by
 *                       {@code global_position}, never {@code null}
 *
 * @see DeliveryMode
 * @see WsSubscription
 * @see <a href="Doc 10 §4.1">Event Delivery</a>
 */
public record EventsMsg(
        Integer id,
        String subscriptionId,
        DeliveryMode deliveryMode,
        List<Object> events
) implements WsMessage {

    /**
     * Creates a new events message with validation and defensive copy.
     *
     * @throws NullPointerException if {@code subscriptionId},
     *                              {@code deliveryMode}, or {@code events}
     *                              is {@code null}
     */
    public EventsMsg {
        Objects.requireNonNull(subscriptionId, "subscriptionId must not be null");
        Objects.requireNonNull(deliveryMode, "deliveryMode must not be null");
        Objects.requireNonNull(events, "events must not be null");
        events = List.copyOf(events);
    }
}
