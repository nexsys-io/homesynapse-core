/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event.bus;

import java.time.Duration;

/**
 * Stateless no-op implementation of {@link BusMetrics}.
 *
 * <p>Used by {@code InMemoryEventBus} and test fixtures where metric emission
 * is not required. All methods return immediately without side effects.</p>
 */
final class NoopBusMetrics implements BusMetrics {

    static final NoopBusMetrics INSTANCE = new NoopBusMetrics();

    private NoopBusMetrics() {
        // Singleton.
    }

    @Override
    public void recordPublishLatency(Duration duration) {
        // No-op.
    }

    @Override
    public void incrementPublisherBlocked() {
        // No-op.
    }

    @Override
    public void recordWriterQueueDepth(int depth) {
        // No-op.
    }

    @Override
    public void recordSubscriberLag(String subscriberId, long lagEvents, Duration lagMillis) {
        // No-op.
    }

    @Override
    public void recordDerivedWriteAccepted(String subscriberId) {
        // No-op.
    }

    @Override
    public void recordDerivedWriteParked(String subscriberId) {
        // No-op.
    }
}
