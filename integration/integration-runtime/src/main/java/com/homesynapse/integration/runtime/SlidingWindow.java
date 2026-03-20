/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.runtime;

/**
 * Point-in-time snapshot of a sliding window used for health score calculation
 * (Doc 05 §3.4).
 *
 * <p>The supervisor maintains three sliding windows per integration: error events,
 * timeout events, and slow-call events. Each window has a configured capacity
 * (default 20, from {@link com.homesynapse.integration.HealthParameters#healthWindowSize()})
 * and tracks the proportion of events that match the monitored condition.</p>
 *
 * <p>The {@link #rate()} is the proportion of matching events in the window:
 * {@code errorRate = errors / windowSize}, {@code timeoutRate = timeouts / windowSize},
 * {@code slowCallRate = slowCalls / windowSize}. A rate of 0.0 means no matching
 * events; 1.0 means every event in the window matched.</p>
 *
 * <p>Phase 3 maintains the window internally using a
 * {@code ConcurrentLinkedDeque<Instant>}; this record captures the observable
 * state for {@link IntegrationHealthRecord} snapshots and REST API exposure.</p>
 *
 * <p>This record is immutable and thread-safe.</p>
 *
 * @param size  the configured window capacity (from
 *              {@link com.homesynapse.integration.HealthParameters#healthWindowSize()});
 *              must be {@code >= 0}
 * @param count the number of events currently in the window; must be
 *              {@code >= 0} and {@code <= size}
 * @param rate  the proportion of matching events ({@code count / size} as a
 *              value between 0.0 and 1.0 inclusive); 0.0 when {@code size} is 0
 *
 * @see IntegrationHealthRecord
 * @see com.homesynapse.integration.HealthParameters#healthWindowSize()
 */
public record SlidingWindow(int size, int count, double rate) {

    /**
     * Validates that {@code size} and {@code count} are non-negative,
     * {@code count} does not exceed {@code size}, and {@code rate} is
     * within the [0.0, 1.0] range.
     *
     * @throws IllegalArgumentException if any constraint is violated
     */
    public SlidingWindow {
        if (size < 0) {
            throw new IllegalArgumentException("size must be non-negative: " + size);
        }
        if (count < 0) {
            throw new IllegalArgumentException("count must be non-negative: " + count);
        }
        if (count > size) {
            throw new IllegalArgumentException(
                    "count must not exceed size: count=%d, size=%d".formatted(count, size));
        }
        if (rate < 0.0 || rate > 1.0) {
            throw new IllegalArgumentException(
                    "rate must be between 0.0 and 1.0 inclusive: " + rate);
        }
    }
}
