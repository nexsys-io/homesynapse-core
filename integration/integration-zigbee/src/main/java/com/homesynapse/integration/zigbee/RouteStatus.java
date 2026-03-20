package com.homesynapse.integration.zigbee;

/**
 * Per-device route health state for passive route monitoring.
 *
 * <p>Route status tracks consecutive command failures to detect and recover from
 * degraded mesh routes. Success reset: any received frame or successful command
 * response resets the status to {@link #HEALTHY}.
 *
 * <p>Doc 08 §3.15 AMD-07.
 *
 * <p>Thread-safe: enum.
 *
 * @see RouteHealth
 */
public enum RouteStatus {

    /** Normal operation. No consecutive command failures. */
    HEALTHY,

    /**
     * 1–2 consecutive command failures. The device is being monitored closely
     * for further failures before triggering route recovery.
     */
    DEGRADED,

    /**
     * 3 or more consecutive command failures. Route recovery has been triggered
     * to re-establish communication with the device.
     */
    UNREACHABLE
}
