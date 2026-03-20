/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.ws;

/**
 * Delivery mode for WebSocket event streaming, reflecting the current
 * backpressure state of a subscription's send buffer.
 *
 * <p>The delivery mode transitions through three stages as the client's
 * send buffer fills (Doc 10 §3.7). Each stage progressively reduces the
 * volume of data sent to a slow consumer while preserving the most
 * important events:</p>
 *
 * <ul>
 *   <li>{@link #NORMAL} — individual event delivery, buffer below threshold</li>
 *   <li>{@link #BATCHED} — accumulated delivery within a batch window</li>
 *   <li>{@link #COALESCED} — deduplicated DIAGNOSTIC events per
 *       {@code (subject_ref, attribute_key)} tuple</li>
 * </ul>
 *
 * <p>Recovery transitions back to {@code NORMAL} when the send buffer drains
 * below {@code recovery_threshold_kb}. The client is always notified of mode
 * changes via {@link DeliveryModeChangedMsg}.</p>
 *
 * <p>This enum is specific to the WebSocket API's client-facing delivery
 * strategy. It is distinct from any event-model processing mode.</p>
 *
 * @see DeliveryModeChangedMsg
 * @see EventsMsg
 * @see <a href="Doc 10 §3.7">Backpressure</a>
 */
public enum DeliveryMode {

    /**
     * Events are delivered individually as they arrive. The send buffer
     * is below the {@code batched_threshold_kb} threshold. This is the
     * default mode for healthy connections.
     */
    NORMAL,

    /**
     * Events are accumulated for up to {@code batch_window_ms} and delivered
     * in multi-event messages. Activated when the send buffer exceeds
     * {@code batched_threshold_kb}. All event types are batched — no
     * deduplication occurs at this stage.
     */
    BATCHED,

    /**
     * Coalescable DIAGNOSTIC events are deduplicated per
     * {@code (subject_ref, attribute_key)} tuple, retaining only the most
     * recent value. Activated when the send buffer exceeds
     * {@code coalesced_threshold_kb}. Only three event types are coalescable:
     * {@code state_reported}, {@code presence_signal}, and
     * {@code telemetry_summary} (Doc 01 §3.6). CRITICAL and NORMAL priority
     * events are still delivered individually even in this mode.
     */
    COALESCED
}
