/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

/**
 * Phases in the four-phase command lifecycle that HomeSynapse exposes through its API.
 *
 * <p>The command lifecycle is HomeSynapse's strongest competitive differentiator at
 * the API surface (Doc 09 §3.4, §4.5). No existing smart home platform exposes the
 * full {@code accepted → dispatched → acknowledged → confirmed} lifecycle through
 * its API. The event-sourced architecture produces these events naturally; the REST
 * API makes them accessible via standard HTTP semantics.</p>
 *
 * <p>Phase ordering matches the event chronology:</p>
 * <ol>
 *   <li>{@link #ACCEPTED} — the {@code command_issued} event is durably persisted.</li>
 *   <li>{@link #DISPATCHED} — the command has been sent to the integration adapter.</li>
 *   <li>{@link #ACKNOWLEDGED} — the integration adapter confirms receipt and
 *       reports the device's response.</li>
 *   <li>{@link #CONFIRMED} — the State Store confirms that the expected state
 *       change materialized.</li>
 * </ol>
 *
 * <p>{@link #CONFIRMATION_TIMED_OUT} is the terminal failure phase — it replaces
 * {@link #CONFIRMED} when the expected state change does not materialize within the
 * configured timeout. A {@code command_confirmation_timed_out} event exists in the
 * event store instead of {@code state_confirmed}.</p>
 *
 * @see CommandStatusResponse
 * @see LifecyclePhaseDetail
 * @see <a href="Doc 09 §3.4">Command Lifecycle</a>
 * @see <a href="Doc 09 §4.5">Command Status Endpoint</a>
 */
public enum CommandLifecyclePhase {

    /**
     * The command has been validated and durably persisted as a {@code command_issued} event.
     *
     * <p>This is the initial phase. "Accepted" means the command is valid and durable —
     * it does NOT mean the device has received or executed the command. The REST API
     * returns {@code 202 Accepted} at this phase.</p>
     */
    ACCEPTED,

    /**
     * The command has been sent to the integration adapter for delivery to the device.
     *
     * <p>A {@code command_dispatched} event records which integration adapter received
     * the command. The adapter is now responsible for delivering it to the physical
     * device.</p>
     */
    DISPATCHED,

    /**
     * The integration adapter confirms receipt and reports the device's response.
     *
     * <p>A {@code command_result} event records the device's acknowledgement. The
     * {@code result} field indicates success or failure at the device level. A
     * successful acknowledgement does not guarantee state confirmation — the device
     * may acknowledge the command but fail to change state.</p>
     */
    ACKNOWLEDGED,

    /**
     * The State Store confirms that the expected state change materialized.
     *
     * <p>A {@code state_confirmed} event records that the materialized state matches
     * the expected outcome of the command. This is the terminal success phase — the
     * command lifecycle is complete.</p>
     */
    CONFIRMED,

    /**
     * The expected state change did not materialize within the configured timeout.
     *
     * <p>A {@code command_confirmation_timed_out} event replaces the expected
     * {@code state_confirmed} event. This is the terminal failure phase. The command
     * was accepted and possibly dispatched, but the system cannot confirm that the
     * device changed state as expected.</p>
     */
    CONFIRMATION_TIMED_OUT
}
