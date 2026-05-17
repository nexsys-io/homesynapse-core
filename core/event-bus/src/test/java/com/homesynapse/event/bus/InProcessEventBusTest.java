/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event.bus;

import com.homesynapse.event.EventPublisher;
import com.homesynapse.event.EventStore;
import com.homesynapse.event.bus.test.EventBusContractTest;
import com.homesynapse.event.bus.test.InMemoryCheckpointStore;
import com.homesynapse.event.bus.test.RecordingReadConnectionFactory;
import com.homesynapse.event.test.InMemoryEventStore;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Concrete subclass exercising the production {@link InProcessEventBus}.
 *
 * <p>Wires the production bus into the {@link EventBusContractTest} abstract
 * contract test suite. Provides a mutable clock for backoff/crash-window testing
 * and a recording {@link SubscriberReadConnectionFactory} for isolation assertions.</p>
 *
 * <p>This test class enables the full 44-method test suite (18 existing Tiers 1–4 +
 * 16 active new Tiers 5–8 + 10 disabled placeholders Tiers 9–10).</p>
 *
 * @see InProcessEventBus
 * @see EventBusContractTest
 */
class InProcessEventBusTest extends EventBusContractTest {

    private static final Instant EPOCH = Instant.parse("2026-05-01T00:00:00Z");

    private MutableClock mutableClock;
    private InMemoryEventStore eventStore;
    private InMemoryCheckpointStore checkpointStore;
    private RecordingReadConnectionFactory recordingFactory;
    private InProcessEventBus bus;

    /** Creates a new test instance. */
    InProcessEventBusTest() {
        // Explicit constructor per -Xlint:all -Werror requirement.
    }

    @Override
    protected EventBus bus() {
        return bus;
    }

    @Override
    protected EventPublisher publisher() {
        return eventStore;
    }

    @Override
    protected EventStore store() {
        return eventStore;
    }

    @Override
    protected CheckpointStore checkpointStore() {
        return checkpointStore;
    }

    @Override
    protected void resetAll() {
        if (bus != null) {
            bus.reset();
        }
        mutableClock = new MutableClock(EPOCH);
        eventStore = new InMemoryEventStore(mutableClock);
        checkpointStore = new InMemoryCheckpointStore();
        recordingFactory = new RecordingReadConnectionFactory();
        bus = new InProcessEventBus(eventStore, checkpointStore,
                mutableClock, recordingFactory);
    }

    @Override
    protected void subscribeWithCallback(SubscriberInfo info, Consumer<Long> handler) {
        bus.subscribeWithHandler(info, handler);
    }

    @Override
    protected boolean supportsActiveRuntime() {
        return true;
    }

    @Override
    protected Clock clock() {
        return mutableClock;
    }

    @Override
    protected SubscriberReadConnectionFactory readConnectionFactory() {
        return recordingFactory;
    }

    @Override
    protected void advanceClock(Duration duration) {
        mutableClock.advance(duration);
    }

    // ──────────────────────────────────────────────────────────────────
    // MutableClock — test clock with advance capability
    // ──────────────────────────────────────────────────────────────────

    /**
     * Mutable clock for testing time-dependent behavior (backoff, crash windows).
     *
     * <p>Starts at a fixed instant and can be advanced by arbitrary durations.
     * Thread-safe via {@link AtomicReference}.</p>
     */
    private static final class MutableClock extends Clock {

        private final AtomicReference<Instant> current;
        private final ZoneOffset zone = ZoneOffset.UTC;

        /** Creates a mutable clock starting at the given instant. */
        MutableClock(Instant start) {
            this.current = new AtomicReference<>(start);
        }

        @Override
        public ZoneOffset getZone() {
            return zone;
        }

        @Override
        public Clock withZone(java.time.ZoneId zoneId) {
            return this; // Ignores zone change for simplicity in tests
        }

        @Override
        public Instant instant() {
            return current.get();
        }

        /**
         * Advances the clock by the given duration.
         *
         * @param duration the amount of time to advance
         */
        void advance(Duration duration) {
            current.updateAndGet(i -> i.plus(duration));
        }
    }
}
