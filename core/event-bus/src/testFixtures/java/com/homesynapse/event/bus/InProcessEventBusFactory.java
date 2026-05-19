/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event.bus;

import com.homesynapse.event.EventStore;

import java.time.Clock;
import java.util.Objects;

/**
 * Public test factory for the package-private {@link InProcessEventBus}.
 *
 * <p>{@code InProcessEventBus} is package-private (and will remain so until
 * the composition-root lifecycle module M3.6 supplies the real wiring).
 * Integration tests in other modules cannot construct it directly. This
 * factory lives in the event-bus {@code testFixtures} source set's main
 * package so it can reach the package-private constructor and return the
 * production bus typed as the public {@link EventBus} interface.</p>
 *
 * <p>This fixture is NOT a replacement for the future composition-root
 * lifecycle wiring (M3.6). It exists solely to make on-device integration
 * testing possible before the composition root lands. Production code MUST
 * NOT depend on this class.</p>
 *
 * @see InProcessEventBus
 * @see EventBus
 */
public final class InProcessEventBusFactory {

    private InProcessEventBusFactory() {
        // Utility — non-instantiable
    }

    /**
     * Constructs the production {@link InProcessEventBus} via its convenience
     * constructor.
     *
     * <p>Wires {@code BusMetrics.noop()} and a constant {@code () -> 0}
     * writer-queue-depth supplier — appropriate for integration tests that
     * do not assert on JFR metrics or saturation behavior. Tests needing
     * those signals should call the production 6-arg constructor directly
     * (which requires same-package access).</p>
     *
     * @param eventStore             the SQLite-backed (or in-memory) event
     *                               store the subscribers will pull from;
     *                               never {@code null}
     * @param checkpointStore        the durable per-subscriber checkpoint
     *                               store; never {@code null}
     * @param clock                  injected clock for all bus-side
     *                               timestamps; never {@code null}
     * @param readConnectionFactory  factory that builds a per-subscriber
     *                               read executor (one platform-thread +
     *                               connection per subscriber in production);
     *                               never {@code null}
     * @return the production bus typed as {@link EventBus}
     */
    public static EventBus create(
            EventStore eventStore,
            CheckpointStore checkpointStore,
            Clock clock,
            SubscriberReadConnectionFactory readConnectionFactory) {
        Objects.requireNonNull(eventStore, "eventStore");
        Objects.requireNonNull(checkpointStore, "checkpointStore");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(readConnectionFactory, "readConnectionFactory");
        return new InProcessEventBus(
                eventStore, checkpointStore, clock, readConnectionFactory);
    }
}
