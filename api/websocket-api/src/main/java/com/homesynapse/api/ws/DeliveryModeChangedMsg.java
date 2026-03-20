/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.ws;

import java.util.Objects;

/**
 * Server-to-client delivery mode change notification
 * (wire type: {@code "delivery_mode_changed"}).
 *
 * <p>Sent when the delivery mode for a subscription changes due to
 * backpressure state transitions. The client always knows whether it is
 * receiving the full event stream or a degraded view.</p>
 *
 * <p>Transition triggers (Doc 10 §3.7):</p>
 * <ul>
 *   <li>{@link DeliveryMode#NORMAL} → {@link DeliveryMode#BATCHED}: send
 *       buffer exceeds {@code batched_threshold_kb}</li>
 *   <li>{@link DeliveryMode#BATCHED} → {@link DeliveryMode#COALESCED}: send
 *       buffer exceeds {@code coalesced_threshold_kb}</li>
 *   <li>Recovery to {@link DeliveryMode#NORMAL}: send buffer drains below
 *       {@code recovery_threshold_kb}</li>
 * </ul>
 *
 * <p>Thread-safe (immutable record).</p>
 *
 * @param id             {@code null} (server-initiated message)
 * @param subscriptionId the affected subscription, never {@code null}
 * @param oldMode        the previous delivery mode, never {@code null}
 * @param newMode        the new delivery mode, never {@code null}
 * @param reason         human-readable transition reason (e.g.,
 *                       {@code "client_buffer_exceeded"},
 *                       {@code "buffer_drained"}), never {@code null}
 *
 * @see DeliveryMode
 * @see EventsMsg
 * @see <a href="Doc 10 §3.7">Backpressure</a>
 */
public record DeliveryModeChangedMsg(
        Integer id,
        String subscriptionId,
        DeliveryMode oldMode,
        DeliveryMode newMode,
        String reason
) implements WsMessage {

    /**
     * Creates a new delivery mode changed message with validation of required fields.
     *
     * @throws NullPointerException if {@code subscriptionId}, {@code oldMode},
     *                              {@code newMode}, or {@code reason} is {@code null}
     */
    public DeliveryModeChangedMsg {
        Objects.requireNonNull(subscriptionId, "subscriptionId must not be null");
        Objects.requireNonNull(oldMode, "oldMode must not be null");
        Objects.requireNonNull(newMode, "newMode must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
    }
}
