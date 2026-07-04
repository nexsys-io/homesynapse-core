/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

/**
 * The load-bearing honest verdict of AMD-97: whether a true {@code CONFIRMED} is
 * achievable for a capability on this specific device at all.
 *
 * <p>{@link #CONFIRMABLE} — the device reliably reports/returns the authoritative
 * attribute; a true {@code CONFIRMED} is achievable. {@link #BEST_EFFORT} —
 * possible but slow/unreliable (sleepy, periodic-only, no Configure-Reporting, or
 * readback-only); tune the timeout up or confirm via explicit readback, and expect
 * honest {@code UNCONFIRMED} under load. {@link #UNCONFIRMABLE} — no authoritative
 * report ever arrives; render {@code UNCONFIRMED} immediately, <strong>never</strong>
 * a false {@code CONFIRMED} (AMD-97-INV-01: a command whose confirmation is
 * {@code DISABLED}/{@code UNCONFIRMABLE} never renders {@code CONFIRMED}).
 *
 * <p>Zigbee-scoped: this type's vocabulary (clusters, endpoints, ZCL data types) is
 * deliberately protocol-specific; it is NOT the generic profile contract. A future
 * cross-protocol profile model is a separate design decision (Doc 18 §3.5(d) seam
 * note).
 *
 * <p>Doc 08 §3.6 {@code confirmation[]} (AMD-97, ratified 2026-07-01).
 *
 * <p>Thread-safe: enum.
 *
 * @see ConfirmationCharacterization
 * @see ReportsAuthoritative
 */
public enum Confirmability {

    /** A true {@code CONFIRMED} is achievable (measured, not assumed). */
    CONFIRMABLE,

    /** Confirmation is possible but slow/unreliable; expect honest {@code UNCONFIRMED}. */
    BEST_EFFORT,

    /** No authoritative confirmation exists; {@code UNCONFIRMED} renders immediately. */
    UNCONFIRMABLE
}
