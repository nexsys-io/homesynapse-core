/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.observability;

/**
 * Tiered classification for health aggregation composition.
 *
 * <p>The system health model divides subsystems into three tiers, each with
 * its own aggregation rule (Doc 11 §3.3, decision D-02/D-03). System-wide
 * health is computed as the worst-of across all tier results.</p>
 *
 * @see HealthStatus
 * @see SystemHealth
 * @see HealthAggregator
 */
public enum HealthTier {
    /**
     * Tier 1 — Critical Infrastructure subsystems (Event Bus, State Store, Persistence).
     *
     * <p>Aggregation rule: Any subsystem reporting {@link HealthStatus#UNHEALTHY}
     * makes the system UNHEALTHY. Any subsystem reporting {@link HealthStatus#DEGRADED}
     * makes the system DEGRADED (unless another tier has UNHEALTHY).</p>
     *
     * <p>Assigned subsystems: event-bus, state-store, persistence.</p>
     */
    CRITICAL_INFRASTRUCTURE,

    /**
     * Tier 2 — Core Services (Automation Engine, Integration Runtime, Configuration,
     * Device Model, Observability).
     *
     * <p>Aggregation rule: Two or more subsystems reporting {@link HealthStatus#DEGRADED}
     * make the system DEGRADED. Any subsystem reporting {@link HealthStatus#UNHEALTHY}
     * makes the system DEGRADED (unless Tier 1 has UNHEALTHY).</p>
     *
     * <p>Assigned subsystems: automation, integration-runtime, configuration,
     * device-model, observability (self).</p>
     */
    CORE_SERVICES,

    /**
     * Tier 3 — Interface Services (REST API, WebSocket API).
     *
     * <p>Aggregation rule: All subsystems in this tier reporting
     * {@link HealthStatus#UNHEALTHY} make the system DEGRADED (not UNHEALTHY).
     * The system remains HEALTHY unless higher tiers report issues.</p>
     *
     * <p>Assigned subsystems: rest-api, websocket-api.</p>
     */
    INTERFACE_SERVICES
}
