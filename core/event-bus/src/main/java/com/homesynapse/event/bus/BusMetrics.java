/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event.bus;

import java.time.Duration;

/**
 * Typed facade for the seven canonical bus metrics (AMD-43 §3.6.2).
 *
 * <p>Every metric emission in the event bus passes through this interface,
 * which decouples the bus from any specific metrics transport. The production
 * implementation ({@link BusMetricsJfr}) commits {@code jdk.jfr.Event}s; the
 * {@link #noop()} implementation is used by {@code InMemoryEventBus} and test
 * fixtures where metric emission is not relevant.</p>
 *
 * <p>The seven canonical metric names are governed by Doc 11's metrics
 * stability policy. The literal strings are bound to JFR event {@code @Name}
 * values in {@link BusMetricsJfr}:</p>
 *
 * <ol>
 *   <li>{@code homesynapse.bus.publish.latency} — histogram, microseconds</li>
 *   <li>{@code homesynapse.bus.publisher.blocked.count} — counter</li>
 *   <li>{@code homesynapse.bus.writer.queue.depth} — gauge, int</li>
 *   <li>{@code homesynapse.bus.subscriber.lag.events} — gauge per subscriberId</li>
 *   <li>{@code homesynapse.bus.subscriber.lag.millis} — gauge per subscriberId</li>
 *   <li>{@code homesynapse.bus.subscriber.derived_writes.accepted} — counter</li>
 *   <li>{@code homesynapse.bus.subscriber.derived_writes.parked} — counter</li>
 * </ol>
 *
 * <p><strong>Thread safety:</strong> All methods may be called concurrently
 * from multiple subscriber virtual threads and the publish notification path.
 * Implementations must be thread-safe and fire-and-forget — no return values,
 * no checked exceptions.</p>
 *
 * @see <a href="AMD-43 §3.6.2">Canonical bus metrics</a>
 */
public interface BusMetrics {

    /**
     * Records the wall-clock duration of bus publish notification fan-out.
     *
     * <p>Emitted as the {@code homesynapse.bus.publish.latency} histogram.</p>
     *
     * @param duration the duration; never {@code null}
     */
    void recordPublishLatency(Duration duration);

    /**
     * Records that the writer queue depth exceeded the blocking threshold
     * (5000) at publish notification entry.
     *
     * <p>Per INV-BUS-02, this is a record-only observation — the publisher
     * does NOT block. Emitted as the
     * {@code homesynapse.bus.publisher.blocked.count} counter.</p>
     */
    void incrementPublisherBlocked();

    /**
     * Records the writer queue depth gauge at sample time.
     *
     * <p>Sampled on every publish notification (AMD-43 §3.6.2 — guaranteed
     * fresh value, not derived from a stale cache). Emitted as the
     * {@code homesynapse.bus.writer.queue.depth} gauge.</p>
     *
     * @param depth the observed depth (>= 0)
     */
    void recordWriterQueueDepth(int depth);

    /**
     * Records per-subscriber lag after a successful event delivery.
     *
     * <p>Emitted as both {@code homesynapse.bus.subscriber.lag.events} (count)
     * and {@code homesynapse.bus.subscriber.lag.millis} (duration) gauges.
     * The two logical metrics share a single JFR event in this
     * implementation.</p>
     *
     * @param subscriberId the subscriber's stable identifier; never {@code null}
     * @param lagEvents    the number of events between the delivered position and
     *                     the writer tail (>= 0)
     * @param lagMillis    the wall-clock duration between event ingest time and
     *                     subscriber observation time; never {@code null}
     */
    void recordSubscriberLag(String subscriberId, long lagEvents, Duration lagMillis);

    /**
     * Records that a derived-write token bucket acquire succeeded immediately.
     *
     * <p>Emitted as the
     * {@code homesynapse.bus.subscriber.derived_writes.accepted} counter.</p>
     *
     * @param subscriberId the subscriber's stable identifier; never {@code null}
     */
    void recordDerivedWriteAccepted(String subscriberId);

    /**
     * Records that a derived-write token bucket acquire had to park.
     *
     * <p>Emitted as the
     * {@code homesynapse.bus.subscriber.derived_writes.parked} counter.</p>
     *
     * @param subscriberId the subscriber's stable identifier; never {@code null}
     */
    void recordDerivedWriteParked(String subscriberId);

    /**
     * Returns a stateless no-op implementation. Used by {@code InMemoryEventBus},
     * test fixtures, and any context where metric emission is not needed.
     *
     * @return the singleton no-op instance
     */
    static BusMetrics noop() {
        return NoopBusMetrics.INSTANCE;
    }

    /**
     * Returns a new JFR-native implementation. The lifecycle module's composition
     * root wires this into the production {@code InProcessEventBus}; the
     * implementation class itself is package-private and accessed only through
     * this factory.
     *
     * @return a fresh JFR-emitting metrics instance
     */
    static BusMetrics jfr() {
        return new BusMetricsJfr();
    }
}
