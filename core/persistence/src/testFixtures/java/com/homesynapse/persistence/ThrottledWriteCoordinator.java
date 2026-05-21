/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;

/**
 * {@link WriteCoordinator} decorator that injects Pi-4-equivalent disk latency
 * on the write thread.
 *
 * <p>Wraps another {@code WriteCoordinator} and, for every submitted operation,
 * adds a baseline delay plus an occasional spike before the underlying
 * {@link Callable} executes. The delays run INSIDE the write thread — after
 * the delegate has dequeued the wrapped Callable but before
 * {@link Callable#call()} returns. This places the delay on the platform
 * write thread (where the JNI {@code fsync} would block on real Pi-4 SD-card
 * storage), not on the caller's virtual thread.</p>
 *
 * <p>Defaults reproduce the D1 spike profile (PLAN-M3-CONSOLIDATED-02 §8.6):
 * 10 ms baseline on every write, 200 ms spike at 0.5% probability. Both are
 * configurable via the constructor; the {@link #withDefaults} factory is the
 * common path.</p>
 *
 * <p>This class lives in the {@code com.homesynapse.persistence} package
 * within the {@code testFixtures} source set, giving it package-private
 * access to {@link WriteCoordinator} and {@link WritePriority}. It is a test
 * fixture only — production code MUST NOT reference it.</p>
 *
 * <p><strong>Thread safety:</strong> stateless apart from {@code final} fields
 * and the delegate. Safe to call from any thread; the underlying coordinator
 * provides the serialization contract.</p>
 *
 * @see WriteCoordinator
 * @see PersistenceTestHarness#startWithWriteCoordinator
 */
final class ThrottledWriteCoordinator implements WriteCoordinator {

    /** Default baseline delay applied to every write (10 ms). */
    static final Duration DEFAULT_BASELINE_DELAY = Duration.ofMillis(10);

    /** Default spike delay applied probabilistically (200 ms). */
    static final Duration DEFAULT_SPIKE_DELAY = Duration.ofMillis(200);

    /** Default spike probability per write (0.5%). */
    static final double DEFAULT_SPIKE_PROBABILITY = 0.005;

    private final WriteCoordinator delegate;
    private final Duration baselineDelay;
    private final Duration spikeDelay;
    private final double spikeProbability;

    /**
     * Creates a throttled decorator with explicit timing parameters.
     *
     * @param delegate         the underlying coordinator to forward operations to;
     *                         never {@code null}
     * @param baselineDelay    duration to sleep on every write; never {@code null},
     *                         must be non-negative
     * @param spikeDelay       additional duration to sleep on a spike; never
     *                         {@code null}, must be non-negative
     * @param spikeProbability probability in {@code [0.0, 1.0]} of a spike on
     *                         any individual write
     * @throws NullPointerException     if {@code delegate}, {@code baselineDelay},
     *                                  or {@code spikeDelay} is {@code null}
     * @throws IllegalArgumentException if {@code spikeProbability} is outside
     *                                  {@code [0.0, 1.0]} or either delay is negative
     */
    ThrottledWriteCoordinator(
            WriteCoordinator delegate,
            Duration baselineDelay,
            Duration spikeDelay,
            double spikeProbability) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.baselineDelay = Objects.requireNonNull(baselineDelay, "baselineDelay");
        this.spikeDelay = Objects.requireNonNull(spikeDelay, "spikeDelay");
        if (baselineDelay.isNegative()) {
            throw new IllegalArgumentException(
                    "baselineDelay must be non-negative, got " + baselineDelay);
        }
        if (spikeDelay.isNegative()) {
            throw new IllegalArgumentException(
                    "spikeDelay must be non-negative, got " + spikeDelay);
        }
        if (spikeProbability < 0.0 || spikeProbability > 1.0) {
            throw new IllegalArgumentException(
                    "spikeProbability must be in [0.0, 1.0], got " + spikeProbability);
        }
        this.spikeProbability = spikeProbability;
    }

    /**
     * Creates a throttled decorator with the default Pi-4 profile: 10 ms
     * baseline, 200 ms spike at 0.5% probability.
     *
     * @param delegate the underlying coordinator; never {@code null}
     * @return a new decorator using the default timing parameters
     */
    static ThrottledWriteCoordinator withDefaults(WriteCoordinator delegate) {
        return new ThrottledWriteCoordinator(
                delegate,
                DEFAULT_BASELINE_DELAY,
                DEFAULT_SPIKE_DELAY,
                DEFAULT_SPIKE_PROBABILITY);
    }

    @Override
    public <T> T submit(WritePriority priority, Callable<T> operation) {
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(operation, "operation");

        Callable<T> throttled = () -> {
            sleep(baselineDelay);
            if (spikeProbability > 0.0
                    && ThreadLocalRandom.current().nextDouble() < spikeProbability) {
                sleep(spikeDelay);
            }
            return operation.call();
        };
        return delegate.submit(priority, throttled);
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    public int queueSize() {
        return delegate.queueSize();
    }

    private static void sleep(Duration d) {
        if (d.isZero()) {
            return;
        }
        try {
            Thread.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Throttled write interrupted", e);
        }
    }
}
