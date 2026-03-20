/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.ws;

/**
 * Root of the WebSocket message type hierarchy.
 *
 * <p>All WebSocket communication uses JSON text frames (LTD-08). Every message
 * follows a common envelope with {@code id} and {@code type} fields on the wire.
 * The {@code type} field discriminates subtypes during deserialization and is
 * handled by {@link MessageCodec} in Phase 3.</p>
 *
 * <p>The sealed hierarchy enables exhaustive pattern matching in the message
 * dispatcher:</p>
 * <ul>
 *   <li><strong>Client-to-server (4 types):</strong> {@link AuthenticateMsg},
 *       {@link SubscribeMsg}, {@link UnsubscribeMsg}, {@link PingMsg}</li>
 *   <li><strong>Server-to-client (9 types):</strong> {@link AuthResultMsg},
 *       {@link SubscriptionConfirmedMsg}, {@link EventsMsg},
 *       {@link StateSnapshotMsg}, {@link DeliveryModeChangedMsg},
 *       {@link ErrorMsg}, {@link PongMsg}, {@link SubscriptionEndedMsg},
 *       {@link ReplayQueuedMsg}</li>
 * </ul>
 *
 * <p>All subtypes are immutable records. Thread-safe.</p>
 *
 * @see MessageCodec
 * @see WebSocketHandler
 * @see <a href="Doc 10 §8.2">Message Types</a>
 */
public sealed interface WsMessage
        permits AuthenticateMsg, SubscribeMsg, UnsubscribeMsg, PingMsg,
                AuthResultMsg, SubscriptionConfirmedMsg, EventsMsg,
                StateSnapshotMsg, DeliveryModeChangedMsg, ErrorMsg,
                PongMsg, SubscriptionEndedMsg, ReplayQueuedMsg {

    /**
     * Returns the client-assigned correlation integer for request-response pairing.
     *
     * <p>Client-to-server messages carry a non-null integer assigned by the client.
     * Server-to-client messages that respond to a client request echo the client's
     * {@code id}. Server-initiated messages (e.g., {@link EventsMsg},
     * {@link DeliveryModeChangedMsg}) return {@code null}.</p>
     *
     * @return the message correlation identifier, or {@code null} for
     *         server-initiated messages
     */
    Integer id();
}
