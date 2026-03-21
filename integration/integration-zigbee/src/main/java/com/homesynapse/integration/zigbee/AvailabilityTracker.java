/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import java.time.Instant;

/**
 * Per-device availability state machine with power-source-aware timeout logic.
 *
 * <p>Tracks device availability based on frame receipt and command results. Mains-powered
 * devices (routers) trigger active pings after 10 minutes of silence. Battery-powered
 * devices (end devices) use passive timeout after 25 hours. Availability transitions
 * produce {@code availability_changed} events with CRITICAL priority for offline
 * transitions and NORMAL priority for online transitions.
 *
 * <p>Doc 08 §8.1.
 *
 * <p>Thread-safe: implementations must be safe for concurrent access.
 *
 * @see AvailabilityReason
 * @see IEEEAddress
 */
public interface AvailabilityTracker {

    /**
     * Updates the last-seen timestamp on any frame receipt from the device.
     *
     * <p>Called on every frame received from the device, regardless of frame type
     * or content. Resets the silence timeout for the device.
     *
     * @param device the device's IEEE address, never {@code null}
     * @param timestamp the frame receipt timestamp, never {@code null}
     */
    void recordFrame(IEEEAddress device, Instant timestamp);

    /**
     * Updates availability state after a command result.
     *
     * <p>A successful command result confirms device reachability. A failed command
     * result contributes to degraded route detection via {@link RouteHealth}.
     *
     * @param device the device's IEEE address, never {@code null}
     * @param success {@code true} if the command succeeded, {@code false} if it failed or timed out
     * @param timestamp the command result timestamp, never {@code null}
     */
    void recordCommandResult(IEEEAddress device, boolean success, Instant timestamp);

    /**
     * Returns the current availability state for the device.
     *
     * @param device the device's IEEE address, never {@code null}
     * @return {@code true} if the device is considered available, {@code false} otherwise
     */
    boolean isAvailable(IEEEAddress device);

    /**
     * Returns the reason for the device's last availability transition.
     *
     * @param device the device's IEEE address, never {@code null}
     * @return the last availability transition reason, never {@code null}
     */
    AvailabilityReason lastReason(IEEEAddress device);
}
