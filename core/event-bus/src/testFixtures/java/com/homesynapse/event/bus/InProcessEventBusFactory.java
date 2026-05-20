/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event.bus;

import com.homesynapse.event.EventStore;

import java.time.Clock;
import java.util.Objects;
import java.util.function.IntSupplier;

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
     * Constructs the production {@link InProcessEventBus} with no-op metrics
     * and a constant zero writer-queue-depth supplier.
     *
     * <p>Appropriate for integration tests that do not assert on bus metrics
     * or saturation behavior. Tests that DO need to observe the seven
     * canonical bus metrics (AMD-43 §3.6.2) should call
     * {@link #createWithMetrics(EventStore, CheckpointStore, Clock,
     * SubscriberReadConnectionFactory, BusMetrics, IntSupplier)} instead.</p>
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
        return createWithMetrics(
                eventStore, checkpointStore, clock, readConnectionFactory,
                BusMetrics.noop(), () -> 0);
    }

    /**
     * Constructs the production {@link InProcessEventBus} with caller-supplied
     * metrics and writer-queue-depth supplier.
     *
     * <p>Routes through the production 6-arg constructor of
     * {@link InProcessEventBus}. The {@code metrics} parameter accepts any
     * {@link BusMetrics} implementation — tests typically pass an in-process
     * recording fixture (e.g. {@code EventBusContractTest.BusMetricsRecorder})
     * to assert on the emitted seven-metric set (AMD-43 §3.6.2). The
     * {@code writerQueueDepth} supplier feeds the writer-queue-depth gauge;
     * tests that do not exercise rate limiting can pass {@code () -> 0}.</p>
     *
     * @param eventStore             the event store
     * @param checkpointStore        the checkpoint store
     * @param clock                  injected clock
     * @param readConnectionFactory  per-subscriber read-executor factory
     * @param metrics                the {@link BusMetrics} implementation;
     *                               never {@code null}
     * @param writerQueueDepth       supplier of the current writer queue
     *                               depth (DEC-M3-14 — the bus holds no
     *                               reference to persistence types);
     *                               never {@code null}
     * @return the production bus typed as {@link EventBus}
     */
    public static EventBus createWithMetrics(
            EventStore eventStore,
            CheckpointStore checkpointStore,
            Clock clock,
            SubscriberReadConnectionFactory readConnectionFactory,
            BusMetrics metrics,
            IntSupplier writerQueueDepth) {
        Objects.requireNonNull(eventStore, "eventStore");
        Objects.requireNonNull(checkpointStore, "checkpointStore");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(readConnectionFactory, "readConnectionFactory");
        Objects.requireNonNull(metrics, "metrics");
        Objects.requireNonNull(writerQueueDepth, "writerQueueDepth");
        return new InProcessEventBus(
                eventStore, checkpointStore, clock, readConnectionFactory,
                metrics, writerQueueDepth);
    }
}
