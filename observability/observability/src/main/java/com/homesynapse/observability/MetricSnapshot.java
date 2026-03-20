/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

package com.homesynapse.observability;

import java.time.Instant;
import java.util.Objects;

/**
 * Aggregated metric values for one flush window, produced by the {@link MetricsStreamBridge}.
 *
 * <p>Each snapshot represents the aggregation of all JFR events of a specific
 * metric type within a single flush window (~1 second) (Doc 11 §3.2, §8.2).
 * The MetricsStreamBridge continuously reads JFR events and produces pre-aggregated
 * snapshots for real-time consumption via the MetricsRegistry and REST API.</p>
 *
 * @see MetricsStreamBridge
 * @see MetricsRegistry
 */
public record MetricSnapshot(
    /**
     * The metric identifier string.
     *
     * <p>Non-null. Examples: "hs_events_append_latency_ms", "hs_jvm_heap_used_bytes",
     * "hs_device_command_success_count". This is the custom JFR event type name
     * (registered via {@link MetricsRegistry#register(String, String)}).</p>
     */
    String metricName,

    /**
     * Minimum observed value in the window.
     *
     * <p>No null constraint. For counters that never increased in the window,
     * this may be Double.NaN or 0.0 depending on the metric type.</p>
     */
    double min,

    /**
     * Maximum observed value in the window.
     *
     * <p>No null constraint. For counters that never increased in the window,
     * this may be Double.NaN or 0.0 depending on the metric type.</p>
     */
    double max,

    /**
     * Number of events aggregated in the window.
     *
     * <p>Must be >= 0. Zero indicates no events of this metric type occurred
     * in the window. Used to distinguish "no activity" (count=0) from
     * "metric undefined" (absent from the snapshot batch).</p>
     */
    long count,

    /**
     * Sum of all observed values in the window.
     *
     * <p>No null constraint. For counters, this is the total incremented.
     * For gauges, this is the sum of all samples (average = sum/count).
     * For timers, this is total elapsed time in milliseconds.</p>
     */
    double sum,

    /**
     * Timestamp of the window's start.
     *
     * <p>Non-null. Marks when this aggregation window began (usually 1 second
     * before windowEnd). Used for time-series graphing.</p>
     */
    Instant windowStart,

    /**
     * Timestamp of the window's end.
     *
     * <p>Non-null. Marks when this aggregation window ended (the onFlush callback time).
     * Used for time-series graphing and gap detection in real-time dashboards.</p>
     */
    Instant windowEnd
) {
    /**
     * Compact constructor validating non-null fields and count constraint.
     *
     * @throws NullPointerException if metricName, windowStart, or windowEnd is null
     * @throws IllegalArgumentException if count < 0
     */
    public MetricSnapshot {
        Objects.requireNonNull(metricName, "metricName cannot be null");
        Objects.requireNonNull(windowStart, "windowStart cannot be null");
        Objects.requireNonNull(windowEnd, "windowEnd cannot be null");
        if (count < 0) {
            throw new IllegalArgumentException("count must be >= 0, got " + count);
        }
    }
}
