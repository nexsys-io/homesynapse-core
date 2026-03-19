/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import java.util.List;

/**
 * Batch write interface for telemetry samples destined for the telemetry ring
 * store (Doc 04 §8.3).
 *
 * <p>{@code TelemetryWriter} is injected into integration adapters via
 * {@link com.homesynapse.integration.IntegrationContext} when the adapter
 * declares {@code RequiredService.TELEMETRY_WRITER} and
 * {@code DataPath.TELEMETRY} in its
 * {@link com.homesynapse.integration.IntegrationDescriptor}. It provides the
 * sole write path for high-frequency numeric telemetry data (e.g., power
 * consumption, temperature readings).</p>
 *
 * <h2>Batch Write Semantics</h2>
 *
 * <p>Adapters accumulate samples and write them in batches (configurable,
 * default 100 samples per batch). The ring store assigns monotonic sequences
 * to each batch on write. Single-sample writes are supported but less
 * efficient — adapters should batch when possible.</p>
 *
 * <h2>Ring Store Overwrite Behavior</h2>
 *
 * <p>The telemetry ring store uses slot-based overwrite:
 * {@code slot = seq % max_rows}. When the ring buffer reaches capacity
 * (default 100,000 rows per Doc 04 §9), the oldest slot is overwritten.
 * This is a circular buffer with fixed capacity — historical samples beyond
 * the ring size are permanently lost. This design bounds storage growth for
 * high-frequency telemetry while maintaining recent data for dashboards and
 * analytics.</p>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>Implementations must be safe for concurrent use. Multiple integration
 * adapters may call {@link #writeSamples(List)} simultaneously from
 * different virtual threads.</p>
 *
 * @see TelemetrySample
 * @see TelemetryQueryService
 * @see RingBufferStats
 * @since 1.0
 */
public interface TelemetryWriter {

    /**
     * Writes a batch of telemetry samples to the ring store.
     *
     * <p>Samples are written atomically within a single transaction. The ring
     * store assigns monotonic sequence numbers and overwrites the oldest slots
     * when the ring is full. This method blocks until the write is durable.</p>
     *
     * @param samples the telemetry samples to write, never {@code null},
     *                must not be empty
     * @throws NullPointerException     if {@code samples} is {@code null}
     * @throws IllegalArgumentException if {@code samples} is empty
     */
    void writeSamples(List<TelemetrySample> samples);
}
