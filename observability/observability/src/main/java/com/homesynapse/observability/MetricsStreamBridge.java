/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

package com.homesynapse.observability;

import java.util.List;
import java.util.function.Consumer;

/**
 * Bridges JFR continuous recording to real-time metric consumers via the RecordingStream API (JEP 349).
 *
 * <p>This interface reads JFR events from the disk repository with ~1–2 second
 * latency from {@code event.commit()} to {@code onEvent()} callback. Pre-aggregates
 * values into per-metric {@link MetricSnapshot} instances (min, max, count, sum
 * per flush window). Pushes aggregated snapshots to subscribers on each
 * {@code onFlush()} callback (~1 per second) (Doc 11 §3.2, §8.1–§8.2).</p>
 *
 * <p>Bounded internal queue: capacity 60 snapshots (decision D-10). Drops oldest
 * snapshot on overflow and increments a dropped counter for observability.</p>
 *
 * <p>Both push and pull consumption patterns are supported:
 * <ul>
 *   <li><strong>Push:</strong> {@link #subscribe(Consumer)} for real-time push
 *       consumers (e.g., Web UI dashboards).</li>
 *   <li><strong>Pull:</strong> {@link #getLatestSnapshot()} for periodic polling
 *       (e.g., REST API endpoint).</li>
 * </ul>
 * <p>Consumers receive a batch of {@link MetricSnapshot} instances (one per metric)
 * per flush window — not individual metric snapshots.</p>
 *
 * @see MetricSnapshot
 * @see MetricsRegistry
 */
public interface MetricsStreamBridge {
    /**
     * Begin streaming from the JFR recording.
     *
     * <p>Registers {@code onEvent()} and {@code onFlush()} callbacks with the
     * RecordingStream. Must be called after JFR recording has started. Idempotent —
     * if already started, this is a no-op.</p>
     *
     * <p>Thread-safe. May be called from any thread.</p>
     *
     * @see #stop()
     */
    void start();

    /**
     * Stop streaming.
     *
     * <p>Releases RecordingStream resources. After this call, no more callbacks
     * are delivered to subscribers. Idempotent — if already stopped, this is a
     * no-op.</p>
     *
     * <p>Thread-safe. May be called from any thread.</p>
     *
     * @see #start()
     */
    void stop();

    /**
     * Return the most recent aggregated metric snapshot batch.
     *
     * <p>For REST API polling (Doc 11 §8.2). Returns a list of {@link MetricSnapshot}
     * instances — one per metric — representing the most recent flush window.
     * Returns an empty list if no snapshots have been produced yet (e.g.,
     * {@code start()} not yet called or no JFR events recorded).</p>
     *
     * <p>Thread-safe. Returns an immutable snapshot.</p>
     *
     * @return a non-null, unmodifiable list of metric snapshots. Each entry
     *         represents one metric in the most recent window.
     *
     * @see MetricSnapshot
     */
    List<MetricSnapshot> getLatestSnapshot();

    /**
     * Register a push consumer for real-time metric snapshots.
     *
     * <p>Consumers receive a batch of {@link MetricSnapshot} instances (one per
     * metric) on each flush window (~1 per second). The batch is immutable and
     * consistent at the flush time.</p>
     *
     * <p>Thread-safe. Subscriptions take effect immediately. The consumer may
     * receive callbacks from different threads (executor depends on Phase 3
     * implementation).</p>
     *
     * @param consumer the consumer callback. Non-null. Called with the batch of
     *        snapshots on each flush window. Must not block for extended periods
     *        (blocks all other subscribers if the consumer is slow).
     *
     * @throws NullPointerException if consumer is null
     *
     * @see #unsubscribe(Consumer)
     */
    void subscribe(Consumer<List<MetricSnapshot>> consumer);

    /**
     * Remove a previously registered push consumer.
     *
     * <p>After this call, the consumer no longer receives callbacks. Idempotent —
     * if the consumer was never registered, this is a no-op.</p>
     *
     * <p>Thread-safe. Unsubscriptions take effect immediately.</p>
     *
     * @param consumer the consumer to unsubscribe. Non-null. Must be the same
     *        object passed to a prior {@link #subscribe(Consumer)} call (identity
     *        comparison, not equals).
     *
     * @throws NullPointerException if consumer is null
     *
     * @see #subscribe(Consumer)
     */
    void unsubscribe(Consumer<List<MetricSnapshot>> consumer);
}
