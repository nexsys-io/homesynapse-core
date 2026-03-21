/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

/**
 * Per-cluster reporting configuration overrides within a device profile.
 *
 * <p>Reporting overrides take precedence over the adapter's default reporting intervals
 * (Doc 08 §3.7 table). They configure the ZCL Configure Reporting command sent during
 * the post-interview setup phase for devices that need non-standard reporting behavior.
 *
 * <p>Doc 08 §3.7 reporting configuration.
 *
 * <p>Thread-safe: immutable record.
 *
 * @param clusterId the ZCL cluster ID for reporting configuration, must be non-negative
 * @param minInterval the minimum reporting interval in seconds, must be non-negative
 * @param maxInterval the maximum reporting interval in seconds, must be positive and greater than {@code minInterval}
 * @param reportableChange the minimum attribute change to trigger a report, must be non-negative
 * @see DeviceProfile
 */
public record ReportingOverride(int clusterId, int minInterval, int maxInterval, int reportableChange) {

    /**
     * Creates a reporting override with validation.
     *
     * @param clusterId must be non-negative
     * @param minInterval must be non-negative
     * @param maxInterval must be positive and greater than {@code minInterval}
     * @param reportableChange must be non-negative
     */
    public ReportingOverride {
        if (clusterId < 0) {
            throw new IllegalArgumentException(
                    "clusterId must be non-negative, got " + clusterId);
        }
        if (minInterval < 0) {
            throw new IllegalArgumentException(
                    "minInterval must be non-negative, got " + minInterval);
        }
        if (maxInterval <= 0) {
            throw new IllegalArgumentException(
                    "maxInterval must be positive, got " + maxInterval);
        }
        if (maxInterval <= minInterval) {
            throw new IllegalArgumentException(
                    "maxInterval must be greater than minInterval, got maxInterval="
                            + maxInterval + ", minInterval=" + minInterval);
        }
        if (reportableChange < 0) {
            throw new IllegalArgumentException(
                    "reportableChange must be non-negative, got " + reportableChange);
        }
    }
}
