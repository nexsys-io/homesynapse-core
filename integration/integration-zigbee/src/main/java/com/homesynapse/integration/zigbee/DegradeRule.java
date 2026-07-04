/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

/**
 * The composable degrade rules of the AMD-97 {@code confirmation[]} block — how a
 * pending command's verdict degrades when the authoritative confirmation path does
 * not deliver. Free-text nuance lives in the characterization's {@code notes}
 * sibling, never in the rule.
 *
 * <p>The four values are the ratified set (AMD-97, verbatim). They compose as a
 * {@code Set}: a confirmable capability typically carries
 * {@link #NO_REPORT_TIMEOUT_TO_UNCONFIRMED} + {@link #NACK_TO_FAILED} (+
 * {@link #CONFIRM_FROM_CACHE_OR_READBACK} for the measured no-change caveat); an
 * unconfirmable one carries {@link #IMMEDIATE_UNCONFIRMED}.
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
public enum DegradeRule {

    /** No authoritative report within the timeout ⇒ {@code UNCONFIRMED}, never {@code FAILED}. */
    NO_REPORT_TIMEOUT_TO_UNCONFIRMED,

    /** An explicit protocol NACK ⇒ {@code FAILED}. */
    NACK_TO_FAILED,

    /** Render {@code UNCONFIRMED} immediately; no report-wait window exists. */
    IMMEDIATE_UNCONFIRMED,

    /** Confirm from the state cache or an explicit readback (the idempotent no-change case). */
    CONFIRM_FROM_CACHE_OR_READBACK
}
