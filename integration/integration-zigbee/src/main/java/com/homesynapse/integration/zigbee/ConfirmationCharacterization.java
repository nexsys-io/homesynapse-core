/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import com.homesynapse.device.ConfirmationMode;

import java.util.Objects;
import java.util.Set;

/**
 * One entry of the AMD-97 {@code confirmation[]} block: the per-device, measured
 * record of whether a specific capability on a specific device can render a true
 * {@code CONFIRMED} at all — and how its verdict degrades honestly when it cannot.
 *
 * <p>This is the schema realization of the {@code confirmed | unconfirmed | failed}
 * differentiator (AMD-97, ratified 2026-07-01): confirmability becomes a per-device,
 * regression-protected, honestly-degrading FACT populated from bench-measured
 * values ({@code nexsys-bench/corpus/devices/*} is the live-value source of truth),
 * not a capability-default assumption. Read-only devices carry an empty block (the
 * measured SNZB-03P case).
 *
 * <p>AMD-97-INV-01 (inviolable): a command whose confirmation is
 * {@code DISABLED}/{@code UNCONFIRMABLE} never renders {@code CONFIRMED}.
 *
 * <p>Zigbee-scoped: this record's vocabulary (clusters, endpoints, ZCL data types)
 * is deliberately protocol-specific; it is NOT the generic profile contract. A
 * future cross-protocol profile model is a separate design decision (Doc 18
 * §3.5(d) seam note).
 *
 * <p>Doc 08 §3.6 as amended by AMD-97; Doc 02 §3.8 per-device confirmability
 * override.
 *
 * <p>Thread-safe: immutable record with a defensively copied set.
 *
 * @param capability the capability key (e.g., {@code "on_off"}), never {@code null}
 * @param confirmationMode the confirmation comparison strategy, inherited from the
 *        Doc 02 §3.6 capability default and per-device overridable, never {@code null}
 * @param authoritativeAttribute the attribute whose report/readback confirms the
 *        command (e.g., {@code "OnOff/0x0000"}); {@code null} if none exists
 * @param reportsAuthoritative how the authoritative attribute is delivered, never {@code null}
 * @param reportingPosture the reporting cadence the device exhibits, never {@code null}
 * @param confirmability the load-bearing honest verdict, never {@code null}
 * @param recommendedTimeoutMs the per-device-tuned confirmation timeout (feeds the
 *        Doc 02 §3.8 {@code default_timeout}); non-negative, {@code 0} when no
 *        report-wait window exists
 * @param degradeRule the composable degrade rule set, never {@code null}
 * @param notes free-text nuance the enum set cannot carry; {@code null} when empty
 * @see DeviceProfile#confirmation()
 * @see Confirmability
 * @see ReportsAuthoritative
 * @see ReportingPosture
 * @see DegradeRule
 */
public record ConfirmationCharacterization(
        String capability,
        ConfirmationMode confirmationMode,
        String authoritativeAttribute,
        ReportsAuthoritative reportsAuthoritative,
        ReportingPosture reportingPosture,
        Confirmability confirmability,
        long recommendedTimeoutMs,
        Set<DegradeRule> degradeRule,
        String notes) {

    /**
     * Creates a confirmation characterization with validation and a defensive copy.
     *
     * @param capability never {@code null}
     * @param confirmationMode never {@code null}
     * @param authoritativeAttribute {@code null} if no authoritative attribute exists
     * @param reportsAuthoritative never {@code null}
     * @param reportingPosture never {@code null}
     * @param confirmability never {@code null}
     * @param recommendedTimeoutMs must be non-negative
     * @param degradeRule never {@code null}
     * @param notes {@code null} when empty
     */
    public ConfirmationCharacterization {
        Objects.requireNonNull(capability, "capability must not be null");
        Objects.requireNonNull(confirmationMode, "confirmationMode must not be null");
        Objects.requireNonNull(reportsAuthoritative,
                "reportsAuthoritative must not be null");
        Objects.requireNonNull(reportingPosture, "reportingPosture must not be null");
        Objects.requireNonNull(confirmability, "confirmability must not be null");
        if (recommendedTimeoutMs < 0) {
            throw new IllegalArgumentException(
                    "recommendedTimeoutMs must be non-negative, got "
                            + recommendedTimeoutMs);
        }
        Objects.requireNonNull(degradeRule, "degradeRule must not be null");
        degradeRule = Set.copyOf(degradeRule);
    }
}
