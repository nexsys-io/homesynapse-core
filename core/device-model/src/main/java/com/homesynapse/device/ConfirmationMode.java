/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

/**
 * Governs how the Pending Command Ledger evaluates whether a command
 * has been successfully executed by the device.
 *
 * <p>Each capability instance declares a confirmation mode as part of
 * its {@link ConfirmationPolicy}. The mode determines the comparison
 * strategy applied to reported attribute values after a command is issued.</p>
 *
 * <p>Defined in Doc 02 §3.8.</p>
 *
 * @see ConfirmationPolicy
 * @see Expectation
 * @see ConfirmationResult
 * @since 1.0
 */
public enum ConfirmationMode {

    /** Confirmed when the reported value exactly matches the commanded value. */
    EXACT_MATCH,

    /** Confirmed when the reported numeric value is within a configured tolerance of the commanded value. */
    TOLERANCE,

    /** Confirmed when the reported enum value matches the expected transition target. */
    ENUM_MATCH,

    /** Confirmed when any change is observed in the authoritative attribute, regardless of direction. */
    ANY_CHANGE,

    /** Command confirmation is disabled for this capability. Used for read-only capabilities. */
    DISABLED
}
