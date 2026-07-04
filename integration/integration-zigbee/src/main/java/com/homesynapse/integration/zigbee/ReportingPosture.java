/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

/**
 * The reporting cadence a device actually exhibits for a capability's authoritative
 * attribute (AMD-97): change-driven, timer-driven, sleep-gated, or absent.
 *
 * <p>Recorded per device from Configure-Reporting acceptance plus observed report
 * behavior (Doc 08 §3.7 posture classification), and consumed by the confirmation
 * engine to tune timeouts and choose degrade paths.
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
 */
public enum ReportingPosture {

    /** Reports arrive when the attribute changes (Configure-Reporting accepted). */
    ON_CHANGE,

    /** Reports arrive on the device's timer schedule, not on change. */
    PERIODIC,

    /** Reports are gated by a sleepy end device's wake schedule. */
    SLEEPY,

    /** The attribute is never reported. */
    NONE
}
