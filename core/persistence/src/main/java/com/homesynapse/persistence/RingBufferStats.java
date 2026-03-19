/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import java.time.Instant;
import java.util.Objects;

/**
 * Diagnostic snapshot of the telemetry ring store state (Doc 04 §8.5).
 *
 * <p>{@code RingBufferStats} provides observability into the telemetry ring
 * buffer's current utilization, sequence positions, and time span. It is
 * returned by {@link TelemetryQueryService#getRingStats()} and consumed by
 * the Observability subsystem (Doc 11) for health dashboards and the REST
 * API (Doc 09) for admin endpoints.</p>
 *
 * <h2>Sequence Semantics</h2>
 *
 * <p>The ring store assigns a monotonic {@code currentSeq} to each batch of
 * samples written. The {@code oldestSeqInRing} indicates the oldest sequence
 * still present — samples with earlier sequences have been overwritten by
 * the circular buffer. The difference {@code currentSeq - oldestSeqInRing}
 * indicates how many write operations are retained.</p>
 *
 * @param maxRows          the configured ring buffer capacity (number of sample
 *                         slots), always positive
 * @param currentSeq       the latest monotonic sequence assigned to a write
 *                         operation
 * @param oldestSeqInRing  the oldest sequence still present in the ring buffer
 * @param distinctEntities the number of distinct entities with telemetry data
 *                         currently in the ring, always non-negative
 * @param oldestTimestamp   the timestamp of the oldest sample in the ring,
 *                         never {@code null}
 * @param newestTimestamp   the timestamp of the newest sample in the ring,
 *                         never {@code null}
 *
 * @see TelemetryQueryService#getRingStats()
 * @see TelemetrySample
 * @since 1.0
 */
public record RingBufferStats(
        int maxRows,
        long currentSeq,
        long oldestSeqInRing,
        int distinctEntities,
        Instant oldestTimestamp,
        Instant newestTimestamp
) {

    /**
     * Validates that all required fields are non-null and within valid ranges.
     */
    public RingBufferStats {
        Objects.requireNonNull(oldestTimestamp, "oldestTimestamp must not be null");
        Objects.requireNonNull(newestTimestamp, "newestTimestamp must not be null");
    }
}
