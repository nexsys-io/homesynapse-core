/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event.bus;

/**
 * Severity level for bus-internal health signals (AMD-43 §3.6.3).
 *
 * <p>This is a bus-internal type — the observability module's
 * {@code HealthStatus} (HEALTHY/DEGRADED/UNHEALTHY) is the system-wide
 * tier model. {@link QueueSaturationHealthCheck} produces these signals;
 * a future bridge in the lifecycle or observability layer translates
 * them to {@code HealthStatus} reports via {@code HealthContributor}.</p>
 */
enum HealthLevel {

    /** Informational signal — used for recovery notifications. */
    INFO,

    /** Warning signal — sustained warn-threshold breach. */
    WARN,

    /** Critical signal — sustained critical-threshold breach. */
    CRITICAL
}
