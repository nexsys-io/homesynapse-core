/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

/**
 * Throttling gate for derived publishes from {@link StateProjection} (AMD-43 §3.6.4,
 * DEC-M3-08).
 *
 * <p>Per AMD-43 §3.6.4, derived-write-producing subscribers (e.g., the entity-state
 * projection) MUST bound their contribution to writer-queue saturation. The bus's
 * package-private {@code DerivedWriteRateLimit} token bucket (M3.3, 200 tokens/sec
 * default) provides the production implementation, but it is not exported from the
 * {@code com.homesynapse.event.bus} module. {@code DerivedPublishGate} is the
 * exported seam that {@link StateProjection} depends on; the composition root
 * adapts the bus's package-private token bucket to this interface, or supplies a
 * custom implementation for tests.</p>
 *
 * <h2>Acquire semantics</h2>
 *
 * <p>{@link #acquire()} returns when a token is available, blocking the calling
 * thread (virtual or platform) until one becomes available. Implementations using
 * {@link java.util.concurrent.Semaphore Semaphore} are virtual-thread-safe — the
 * carrier thread unmounts during the park.</p>
 *
 * <p>If the calling thread is interrupted while parked, {@link InterruptedException}
 * is thrown and the projection bails out of its current {@code onEvent} call. The
 * subscriber supervisor (AMD-42 §3.4.5) handles teardown.</p>
 *
 * <h2>Unbounded factory</h2>
 *
 * <p>The {@link #unbounded()} factory returns a no-op gate that never blocks. Used
 * by tests that do not exercise rate limiting and by integration paths where the
 * downstream writer is independently bounded.</p>
 *
 * @see StateProjection
 */
@FunctionalInterface
public interface DerivedPublishGate {

    /**
     * Acquires one publish permit, blocking until one is available.
     *
     * @throws InterruptedException if the calling thread is interrupted while
     *                              waiting for a permit
     */
    void acquire() throws InterruptedException;

    /**
     * Returns a no-op gate that grants permits without blocking. Suitable for
     * tests and for wiring contexts where rate limiting is enforced elsewhere.
     *
     * @return an unbounded gate that never blocks
     */
    static DerivedPublishGate unbounded() {
        return () -> {
            // No-op — permits are unbounded.
        };
    }
}
