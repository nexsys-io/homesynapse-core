/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import com.homesynapse.platform.identity.EntityId;

import java.time.Instant;
import java.util.List;

/**
 * Read-only query interface for telemetry data in the ring store
 * (Doc 04 §8.5).
 *
 * <p>{@code TelemetryQueryService} provides access to raw telemetry samples
 * and ring buffer diagnostics. It is consumed by the REST API (Doc 09) for
 * telemetry chart endpoints that render time-series data on the observability
 * dashboard.</p>
 *
 * <h2>Query Scope</h2>
 *
 * <p>Queries are scoped to a single entity and attribute key within a time
 * range. The {@code maxResults} parameter limits the number of samples
 * returned, enabling pagination and preventing unbounded result sets. Results
 * are ordered by timestamp ascending.</p>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>Implementations must be safe for concurrent use. Multiple REST API
 * handlers may query telemetry data simultaneously from different virtual
 * threads. This is a read-only interface — it does not modify the ring
 * store.</p>
 *
 * @see TelemetrySample
 * @see TelemetryWriter
 * @see RingBufferStats
 * @since 1.0
 */
public interface TelemetryQueryService {

    /**
     * Queries raw telemetry samples for a specific entity and attribute within
     * a time range.
     *
     * <p>Returns up to {@code maxResults} samples ordered by timestamp
     * ascending. If the time range contains more samples than
     * {@code maxResults}, only the earliest samples are returned.</p>
     *
     * @param entityRef    the entity to query telemetry for, never {@code null}
     * @param attributeKey the attribute name to filter by (e.g.,
     *                     {@code "current_power_w"}), never {@code null}
     * @param from         the inclusive start of the time range,
     *                     never {@code null}
     * @param to           the exclusive end of the time range,
     *                     never {@code null}
     * @param maxResults   maximum number of samples to return, must be positive
     * @return an unmodifiable list of matching samples ordered by timestamp
     *         ascending, never {@code null}, may be empty
     * @throws NullPointerException     if any object parameter is {@code null}
     * @throws IllegalArgumentException if {@code maxResults} is not positive
     */
    List<TelemetrySample> querySamples(EntityId entityRef, String attributeKey,
                                       Instant from, Instant to, int maxResults);

    /**
     * Returns a diagnostic snapshot of the telemetry ring store state.
     *
     * <p>The snapshot reflects the ring buffer's current capacity utilization,
     * sequence positions, entity count, and time span. Used by the
     * Observability subsystem (Doc 11) for health dashboards.</p>
     *
     * @return the current ring buffer statistics, never {@code null}
     */
    RingBufferStats getRingStats();
}
