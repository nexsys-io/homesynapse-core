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
 * Public test factory for {@link InProcessEventBus}.
 *
 * <p>The bus class itself was promoted to {@code public} in M3.6b
 * (DEC-M3-16). This factory remains in {@code testFixtures} so existing
 * test sites continue to obtain the bus typed as the public
 * {@link EventBus} interface — keeping cross-module test code from
 * accidentally coupling to the concrete production type.</p>
 *
 * <p>This fixture is NOT a replacement for the future composition-root
 * lifecycle wiring (M3.6d). It exists to make on-device integration
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
        return createWithConfig(eventStore, checkpointStore, clock,
                readConnectionFactory, metrics, writerQueueDepth,
                EventBusConfig.HOME_DEFAULT);
    }

    /**
     * Constructs the production {@link InProcessEventBus} with caller-supplied
     * metrics, writer-queue-depth supplier, AND
     * {@link EventBusConfig} (M3.6b).
     *
     * <p>Tests that need to assert overflow behaviour at a smaller replay
     * window capacity (or to exercise a non-default publisher-blocked
     * threshold) pass an explicit config here. Tests that do not care
     * should keep calling {@link #createWithMetrics(EventStore,
     * CheckpointStore, Clock, SubscriberReadConnectionFactory, BusMetrics,
     * IntSupplier)} or {@link #create(EventStore, CheckpointStore, Clock,
     * SubscriberReadConnectionFactory)} — both delegate here with
     * {@link EventBusConfig#HOME_DEFAULT} so behaviour is unchanged.</p>
     *
     * @param eventStore             the event store
     * @param checkpointStore        the checkpoint store
     * @param clock                  injected clock
     * @param readConnectionFactory  per-subscriber read-executor factory
     * @param metrics                the {@link BusMetrics} implementation
     * @param writerQueueDepth       supplier of the current writer queue depth
     * @param config                 bus configuration; never {@code null}
     * @return the production bus typed as {@link EventBus}
     */
    public static EventBus createWithConfig(
            EventStore eventStore,
            CheckpointStore checkpointStore,
            Clock clock,
            SubscriberReadConnectionFactory readConnectionFactory,
            BusMetrics metrics,
            IntSupplier writerQueueDepth,
            EventBusConfig config) {
        Objects.requireNonNull(eventStore, "eventStore");
        Objects.requireNonNull(checkpointStore, "checkpointStore");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(readConnectionFactory, "readConnectionFactory");
        Objects.requireNonNull(metrics, "metrics");
        Objects.requireNonNull(writerQueueDepth, "writerQueueDepth");
        Objects.requireNonNull(config, "config");
        return new InProcessEventBus(
                eventStore, checkpointStore, clock, readConnectionFactory,
                metrics, writerQueueDepth, config);
    }
}
