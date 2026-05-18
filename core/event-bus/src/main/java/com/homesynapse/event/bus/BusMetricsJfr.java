/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event.bus;

import java.time.Duration;
import java.util.Objects;

/**
 * JFR-native {@link BusMetrics} implementation (AMD-43 §3.6.2).
 *
 * <p>Each method constructs the corresponding {@code jdk.jfr.Event} subclass,
 * sets its fields, and calls {@link jdk.jfr.Event#commit() commit()}. Commit
 * is a thread-local buffer write — no synchronization, no I/O — so this
 * implementation is safe for high-frequency emission across many concurrent
 * subscriber virtual threads.</p>
 *
 * <p>The existing {@code MetricsStreamBridge} in {@code observability/observability}
 * (Phase 2) consumes these events via its {@code RecordingStream}, applies
 * pre-aggregation, and produces {@code MetricSnapshot} batches for downstream
 * dashboard and REST consumers. M3.3 introduces no new types in the
 * observability module — the JFR-native emission path is the chosen design
 * (M3.3 deliberation, Decision 1).</p>
 *
 * <p><strong>Design debt (accepted):</strong> When a pull-based metrics
 * consumer (Prometheus scrape, OTLP export) is introduced — likely M4+ —
 * a typed primitive adapter layer (Counter/Gauge/Histogram interfaces in
 * {@code observability/observability}) will be needed to bridge JFR's
 * push-based stream model to a pull-based scrape endpoint. That adapter
 * wraps the JFR events; it does not replace the emission path here.</p>
 */
final class BusMetricsJfr implements BusMetrics {

    BusMetricsJfr() {
        // Package-private constructor.
    }

    @Override
    public void recordPublishLatency(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        BusPublishLatencyEvent event = new BusPublishLatencyEvent();
        if (event.isEnabled()) {
            event.latencyMicros = toMicros(duration);
            event.commit();
        }
    }

    @Override
    public void incrementPublisherBlocked() {
        BusPublisherBlockedEvent event = new BusPublisherBlockedEvent();
        if (event.isEnabled()) {
            event.commit();
        }
    }

    @Override
    public void recordWriterQueueDepth(int depth) {
        BusWriterQueueDepthEvent event = new BusWriterQueueDepthEvent();
        if (event.isEnabled()) {
            event.depth = depth;
            event.commit();
        }
    }

    @Override
    public void recordSubscriberLag(String subscriberId, long lagEvents, Duration lagMillis) {
        Objects.requireNonNull(subscriberId, "subscriberId");
        Objects.requireNonNull(lagMillis, "lagMillis");
        BusSubscriberLagEvent event = new BusSubscriberLagEvent();
        if (event.isEnabled()) {
            event.subscriberId = subscriberId;
            event.lagEvents = lagEvents;
            event.lagMillis = lagMillis.toMillis();
            event.commit();
        }
    }

    @Override
    public void recordDerivedWriteAccepted(String subscriberId) {
        Objects.requireNonNull(subscriberId, "subscriberId");
        BusWriteAcceptedEvent event = new BusWriteAcceptedEvent();
        if (event.isEnabled()) {
            event.subscriberId = subscriberId;
            event.commit();
        }
    }

    @Override
    public void recordDerivedWriteParked(String subscriberId) {
        Objects.requireNonNull(subscriberId, "subscriberId");
        BusWriteParkedEvent event = new BusWriteParkedEvent();
        if (event.isEnabled()) {
            event.subscriberId = subscriberId;
            event.commit();
        }
    }

    /**
     * Saturating conversion from {@link Duration} to whole microseconds.
     *
     * <p>{@link Duration#toNanos()} throws on overflow; we clamp to
     * {@link Long#MAX_VALUE} to keep metric emission fire-and-forget.</p>
     *
     * @param duration the duration (non-null)
     * @return the duration in microseconds, saturated
     */
    private static long toMicros(Duration duration) {
        long seconds = duration.getSeconds();
        int nanos = duration.getNano();
        // Detect overflow before multiplication
        if (seconds > Long.MAX_VALUE / 1_000_000L) {
            return Long.MAX_VALUE;
        }
        if (seconds < Long.MIN_VALUE / 1_000_000L) {
            return Long.MIN_VALUE;
        }
        return seconds * 1_000_000L + nanos / 1_000L;
    }
}
