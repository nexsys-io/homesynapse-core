/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

package com.homesynapse.observability;

/**
 * Three-state health model for subsystem and system-wide health reporting.
 *
 * <p>This enum defines the three possible health states for a subsystem or the
 * entire system. Doc 11 §4.1 governs this definition. Note that Doc 01's
 * {@code CRITICAL} health state maps to {@code UNHEALTHY} in this model (Doc 11
 * §7.1).</p>
 *
 * @see HealthTier
 * @see SystemHealth
 * @see HealthAggregator
 */
public enum HealthStatus {
    /**
     * Subsystem is operating normally within all parameters.
     *
     * <p>No functional degradation, no performance impact, no latency or
     * throughput concerns.</p>
     */
    HEALTHY,

    /**
     * Subsystem is functional but with reduced capability or performance.
     *
     * <p>Examples: stale cached data, dropped metrics due to queue overflow,
     * partial network connectivity, single-threaded mode due to lock contention,
     * raised latency within acceptable bounds. The subsystem can still fulfill
     * its primary function.</p>
     */
    DEGRADED,

    /**
     * Subsystem is unable to perform its primary function.
     *
     * <p>Examples: database connection failed, integration adapter crashed,
     * critical resource exhausted (disk, memory), API timeouts, lock acquisition
     * failed after retry. The subsystem cannot execute its core responsibility.</p>
     *
     * <p>Note: Doc 01's {@code CRITICAL} health state maps to {@code UNHEALTHY}
     * in this three-state model.</p>
     */
    UNHEALTHY
}
