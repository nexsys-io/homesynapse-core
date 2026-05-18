/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event.bus;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Per-subscriber token bucket rate limit for derived-write-producing subscribers
 * (AMD-43 §3.6.4).
 *
 * <p>Designed for State Projection (M3.5a) and other subscribers that produce
 * derived writes from upstream events — they must be bounded in their
 * contribution to writer saturation. M3.3 lands the standalone primitive;
 * the bus does not yet instantiate one (M3.5a will wire it onto
 * derivation-producing subscribers per
 * {@link SubscriberRuntime#rateLimit()}).</p>
 *
 * <h2>Token bucket</h2>
 * <ul>
 *   <li>Bucket starts full (capacity tokens, default 200).</li>
 *   <li>Refill rate: 10 tokens per 50 ms — 200 tokens/sec effective.</li>
 *   <li>Refill is driven externally (a {@link java.util.concurrent.ScheduledExecutorService}
 *       in production; tests call {@link #refill()} directly with a fixed clock
 *       to keep timing deterministic).</li>
 *   <li>{@link #acquire()} blocks the calling virtual thread on a {@link Semaphore}
 *       when no tokens are available — VTs unmount their carrier on
 *       {@code Semaphore.acquire()}, so this does not pin a platform thread.</li>
 * </ul>
 *
 * <h2>Concurrency</h2>
 * <p>Uses {@link AtomicInteger} for the token count, {@link Semaphore} for
 * parking, and {@link ReentrantLock} for compound refill+release operations
 * (LTD-11 — no {@code synchronized}).</p>
 *
 * <h2>Lifecycle</h2>
 * <p>{@link #close()} releases all parked threads and marks the limiter as
 * closed — subsequent {@link #acquire()} calls return immediately with the
 * limiter in a degraded state (the subscriber is being torn down anyway).</p>
 */
final class DerivedWriteRateLimit implements AutoCloseable {

    /** Default capacity per AMD-43 §3.6.4. */
    static final int DEFAULT_CAPACITY = 200;

    /** Tokens added per refill tick. */
    static final int REFILL_TOKENS_PER_TICK = 10;

    /** Refill tick period in milliseconds. */
    static final long REFILL_TICK_MILLIS = 50L;

    private final int capacity;
    private final Clock clock;
    private final BusMetrics metrics;
    private final String subscriberId;

    private final AtomicInteger available;
    private final Semaphore parking = new Semaphore(0);
    private final AtomicInteger parkedCount = new AtomicInteger(0);
    private final ReentrantLock refillLock = new ReentrantLock();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Creates a new rate limit with the default capacity (200).
     *
     * @param clock        the injected clock (currently retained for future
     *                     time-aware tuning; do not call
     *                     {@link Clock#instant()} for control-flow decisions
     *                     in this revision)
     * @param metrics      the metrics emitter; never {@code null}
     * @param subscriberId the subscriber's stable identifier; never {@code null}
     */
    DerivedWriteRateLimit(Clock clock, BusMetrics metrics, String subscriberId) {
        this(DEFAULT_CAPACITY, clock, metrics, subscriberId);
    }

    /**
     * Creates a new rate limit with the given capacity.
     *
     * @param capacity     the bucket capacity (must be positive)
     * @param clock        the injected clock; never {@code null}
     * @param metrics      the metrics emitter; never {@code null}
     * @param subscriberId the subscriber's stable identifier; never {@code null}
     */
    DerivedWriteRateLimit(int capacity, Clock clock, BusMetrics metrics, String subscriberId) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.capacity = capacity;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.subscriberId = Objects.requireNonNull(subscriberId, "subscriberId");
        this.available = new AtomicInteger(capacity);
    }

    /**
     * Acquires one token, blocking the calling thread until one is available or
     * the limiter is closed.
     *
     * <p>If a token is available, {@link BusMetrics#recordDerivedWriteAccepted(String)}
     * is invoked and the method returns immediately. Otherwise the calling
     * thread parks on the internal semaphore after invoking
     * {@link BusMetrics#recordDerivedWriteParked(String)}; the next
     * {@link #refill()} releases one permit per added token, waking parked
     * threads in FIFO order.</p>
     *
     * @throws InterruptedException if the calling thread is interrupted while parked
     */
    void acquire() throws InterruptedException {
        if (closed.get()) {
            return;
        }
        // Fast path — try to take a token without parking.
        if (tryConsume()) {
            metrics.recordDerivedWriteAccepted(subscriberId);
            return;
        }
        // Slow path — record park metric, then await a permit.
        metrics.recordDerivedWriteParked(subscriberId);
        parkedCount.incrementAndGet();
        try {
            while (!closed.get()) {
                parking.acquire();
                if (closed.get()) {
                    return;
                }
                if (tryConsume()) {
                    metrics.recordDerivedWriteAccepted(subscriberId);
                    return;
                }
                // Spurious wakeup (a refill released a permit but another waiter
                // grabbed the token first) — re-park.
            }
        } finally {
            parkedCount.decrementAndGet();
        }
    }

    /**
     * Adds up to {@link #REFILL_TOKENS_PER_TICK} tokens (capped at capacity) and
     * releases that many permits to wake parked threads.
     *
     * <p>Called by a {@link java.util.concurrent.ScheduledExecutorService} task
     * every {@link #REFILL_TICK_MILLIS} milliseconds in production, or by tests
     * directly for deterministic timing. The refill timing budget is informed
     * by the injected {@link Clock} when production wiring lands; in this
     * revision the clock is used only to anchor future tuning decisions.</p>
     */
    void refill() {
        if (closed.get()) {
            return;
        }
        refillLock.lock();
        try {
            int current = available.get();
            int headroom = capacity - current;
            if (headroom <= 0) {
                return;
            }
            int added = Math.min(REFILL_TOKENS_PER_TICK, headroom);
            available.addAndGet(added);
            // Release at most as many permits as we have parked waiters — a
            // refill token consumed by a non-parked caller via tryConsume() is
            // not a wakeup signal.
            int parked = parkedCount.get();
            int permits = Math.min(added, parked);
            if (permits > 0) {
                parking.release(permits);
            }
        } finally {
            refillLock.unlock();
        }
    }

    /**
     * Returns the current bucket capacity (configured at construction).
     *
     * @return the capacity
     */
    int capacity() {
        return capacity;
    }

    /**
     * Returns the current token count. For tests only.
     *
     * @return the available tokens
     */
    int available() {
        return available.get();
    }

    /**
     * Returns the configured clock. Retained for future tuning.
     *
     * @return the clock
     */
    Clock clock() {
        return clock;
    }

    /**
     * Returns the subscriber identifier associated with this limiter.
     *
     * @return the subscriber id
     */
    String subscriberId() {
        return subscriberId;
    }

    /**
     * Releases all parked threads and marks the limiter closed.
     *
     * <p>Idempotent — subsequent calls have no effect. Subsequent
     * {@link #acquire()} calls return immediately. The bus's subscriber teardown
     * calls this from {@link SubscriberRuntime#close()}.</p>
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        // Wake any parked waiters so they observe the closed flag.
        int parked = parkedCount.get();
        if (parked > 0) {
            parking.release(parked);
        }
    }

    /**
     * Attempts to decrement the available count without blocking.
     *
     * @return {@code true} if a token was claimed
     */
    private boolean tryConsume() {
        while (true) {
            int current = available.get();
            if (current <= 0) {
                return false;
            }
            if (available.compareAndSet(current, current - 1)) {
                return true;
            }
        }
    }
}
