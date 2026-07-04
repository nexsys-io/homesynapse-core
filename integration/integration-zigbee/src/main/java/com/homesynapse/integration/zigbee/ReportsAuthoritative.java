/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

/**
 * Whether the authoritative attribute for a capability is delivered by unsolicited
 * reports, only by explicit readback, or does not exist at all (AMD-97).
 *
 * <p>Paired with {@link Confirmability}, this carries the measured taxonomy split
 * (bench E5-#5): "attribute never reported" ({@link #READBACK_ONLY}, the Hue
 * {@code color_loop_active 0x4002} class) and "no attribute" ({@link #NONE}, the
 * {@code identify} class) are distinct sub-cases of an unconfirmable capability —
 * without a fourth {@link Confirmability} verdict value.
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
 * @see Confirmability
 */
public enum ReportsAuthoritative {

    /** The authoritative attribute arrives via unsolicited reports (measured). */
    VERIFIED_REPORTS,

    /**
     * The authoritative attribute exists and is readable on demand but is never
     * reported (the measured Hue {@code color_loop_active} class).
     */
    READBACK_ONLY,

    /** No authoritative attribute returns at all (the measured {@code identify} class). */
    NONE
}
