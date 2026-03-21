/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.lifecycle;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

import com.homesynapse.observability.HealthStatus;

/**
 * Point-in-time snapshot of the entire system's lifecycle and health state.
 *
 * <p>Captured at each health check iteration (Doc 12 §3.10) and made available
 * via {@link SystemLifecycleManager#healthSnapshot()}. This data structure is
 * consumed by the REST API health endpoints (Doc 09) and WebSocket health
 * streaming (Doc 10).</p>
 *
 * @param timestamp when this snapshot was captured
 * @param subsystemStates per-subsystem state detail, keyed by subsystem
 *        identifier (e.g., "event-bus", "state-store"); unmodifiable
 * @param aggregatedHealth system-wide health status — worst-of across all
 *        subsystems, per the three-tier model in Doc 11 §3.3
 * @param uptime time since the system reached {@link LifecyclePhase#RUNNING}
 *        state, or {@code null} if the system has not yet reached RUNNING
 * @param eventStorePosition the highest {@code global_position} in the event
 *        store at snapshot time
 * @param entityCount number of {@code Entity} objects in the Device Registry
 * @param integrationCount number of running integration adapters
 * @param automationCount number of loaded automation definitions
 *
 * @see SystemLifecycleManager#healthSnapshot()
 * @see SubsystemState
 */
public record SystemHealthSnapshot(
        Instant timestamp,
        Map<String, SubsystemState> subsystemStates,
        HealthStatus aggregatedHealth,
        Duration uptime,
        long eventStorePosition,
        int entityCount,
        int integrationCount,
        int automationCount
) {

    /**
     * Creates a validated system health snapshot with defensive copy of the
     * subsystem states map.
     *
     * @throws NullPointerException if {@code timestamp}, {@code subsystemStates},
     *         or {@code aggregatedHealth} is {@code null}
     */
    public SystemHealthSnapshot {
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        Objects.requireNonNull(subsystemStates, "subsystemStates must not be null");
        Objects.requireNonNull(aggregatedHealth, "aggregatedHealth must not be null");
        // uptime is nullable — null until the system reaches RUNNING state
        subsystemStates = Map.copyOf(subsystemStates);
    }
}
