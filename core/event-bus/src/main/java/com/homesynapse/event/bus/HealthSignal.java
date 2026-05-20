/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event.bus;

import java.time.Instant;
import java.util.Objects;

/**
 * Bus-internal health signal emitted by {@link QueueSaturationHealthCheck}
 * (AMD-43 §3.6.3).
 *
 * <p>Carried via a {@code Consumer<HealthSignal>} callback injected at
 * construction time. The health check does NOT depend on the observability
 * module's {@code HealthAggregator} or {@code HealthContributor} —
 * functional decoupling keeps this module's dependency graph clean and
 * preserves the JDBC-free / observability-free constraints documented in
 * the module-info ({@code requires transitive com.homesynapse.event} only).</p>
 *
 * @param level     the severity level
 * @param channel   the logical channel name (e.g. {@code "writer.queue.saturating"})
 * @param depth     the observed queue depth at signal time
 * @param timestamp when the signal was emitted (from the injected {@code Clock})
 */
public record HealthSignal(
        HealthLevel level,
        String channel,
        int depth,
        Instant timestamp
) {

    /**
     * Compact constructor validating non-null components.
     *
     * @throws NullPointerException if {@code level}, {@code channel}, or
     *                              {@code timestamp} is {@code null}
     */
    public HealthSignal {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(timestamp, "timestamp");
    }
}
