/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.spike.wal;

import java.util.Arrays;

/**
 * Collects nanosecond latency samples and computes percentiles using a sorted-array
 * approach. No external statistics library required.
 *
 * <p>Not thread-safe — intended for single-threaded spike data collection.
 */
public final class LatencyStats {

    private long[] samples;
    private int count;
    private boolean sorted;

    /**
     * Creates a new collector pre-sized for the expected number of samples.
     *
     * @param initialCapacity expected number of samples (avoids resizing)
     */
    public LatencyStats(int initialCapacity) {
        this.samples = new long[initialCapacity];
        this.count = 0;
        this.sorted = false;
    }

    /**
     * Records a single latency measurement.
     *
     * @param nanos elapsed time in nanoseconds
     */
    public void record(long nanos) {
        if (count == samples.length) {
            samples = Arrays.copyOf(samples, samples.length * 2);
        }
        samples[count] = nanos;
        count++;
        sorted = false;
    }

    /** Returns the number of recorded samples. */
    public int count() {
        return count;
    }

    /** Returns the arithmetic mean latency in nanoseconds. */
    public double mean() {
        if (count == 0) {
            throw new IllegalStateException("No samples recorded");
        }
        long sum = 0;
        for (int i = 0; i < count; i++) {
            sum += samples[i];
        }
        return (double) sum / count;
    }

    /** Returns the 50th percentile (median) latency in nanoseconds. */
    public long p50() {
        return percentile(50);
    }

    /** Returns the 95th percentile latency in nanoseconds. */
    public long p95() {
        return percentile(95);
    }

    /** Returns the 99th percentile latency in nanoseconds. */
    public long p99() {
        return percentile(99);
    }

    private long percentile(int p) {
        if (count == 0) {
            throw new IllegalStateException("No samples recorded");
        }
        ensureSorted();
        int index = (int) ((p / 100.0) * (count - 1));
        return samples[index];
    }

    private void ensureSorted() {
        if (!sorted) {
            Arrays.sort(samples, 0, count);
            sorted = true;
        }
    }
}
