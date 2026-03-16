/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

/**
 * Indicates the direction of energy flow for an energy metering capability.
 *
 * <p>Used by the {@link EnergyMeter} capability to distinguish between
 * grid-to-home consumption, home-to-grid generation (e.g., solar panels),
 * and bidirectional meters that support both.</p>
 *
 * <p>Defined in Doc 02 §3.6.</p>
 *
 * @see EnergyMeter
 * @since 1.0
 */
public enum EnergyDirection {

    /** Grid-to-home consumption — energy imported from the utility grid. */
    IMPORT,

    /** Home-to-grid generation — energy exported to the utility grid (e.g., from solar panels). */
    EXPORT,

    /** Both import and export are supported — the meter tracks bidirectional energy flow. */
    BIDIRECTIONAL
}
