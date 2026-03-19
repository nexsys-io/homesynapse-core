/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import com.homesynapse.platform.identity.EntityId;

import java.time.Instant;
import java.util.Objects;

/**
 * The atomic unit of telemetry data — a single numeric measurement captured by
 * an integration adapter and written to the telemetry ring store (Doc 04 §3.3).
 *
 * <p>{@code TelemetrySample} represents one time-stamped numeric reading for a
 * specific entity attribute. Integration adapters accumulate samples and write
 * them in batches via {@link TelemetryWriter#writeSamples(java.util.List)}.
 * The ring store assigns monotonic sequences to each sample on write.</p>
 *
 * <h2>Numeric Values Only</h2>
 *
 * <p>The {@code value} field is always a {@code double}. Non-numeric telemetry
 * (strings, enums, booleans) is not supported by the telemetry ring store.
 * Non-numeric attribute changes are captured through the event model as
 * {@code state_reported} events, not as telemetry samples.</p>
 *
 * <h2>Ring Store Semantics</h2>
 *
 * <p>Samples are stored in a fixed-capacity ring buffer (default 100,000 rows
 * per Doc 04 §9). When the ring is full, the oldest sample is overwritten.
 * This is a circular buffer, not an append log — historical samples beyond
 * the ring capacity are permanently lost.</p>
 *
 * @param entityRef    the entity that produced this measurement, never {@code null}
 * @param attributeKey the attribute name within the entity's capability schema
 *                     (e.g., {@code "current_power_w"}, {@code "temperature_c"}),
 *                     never {@code null}
 * @param value        the numeric measurement value
 * @param timestamp    the wall-clock time when the measurement was taken,
 *                     never {@code null}
 *
 * @see TelemetryWriter
 * @see TelemetryQueryService
 * @see RingBufferStats
 * @since 1.0
 */
public record TelemetrySample(
        EntityId entityRef,
        String attributeKey,
        double value,
        Instant timestamp
) {

    /**
     * Validates that all required fields are non-null.
     */
    public TelemetrySample {
        Objects.requireNonNull(entityRef, "entityRef must not be null");
        Objects.requireNonNull(attributeKey, "attributeKey must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");
    }
}
