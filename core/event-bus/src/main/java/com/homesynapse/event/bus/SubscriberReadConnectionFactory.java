/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event.bus;

/**
 * Factory for per-subscriber dedicated read executors (DP-4, FP-4).
 *
 * <p>Each subscriber registered via {@link EventBus#subscribeRuntime(SubscriberInfo, Subscriber)}
 * receives its own {@link SubscriberReadExecutor}, which encapsulates a dedicated platform
 * thread and SQLite read connection. This keeps the event-bus module JDBC-free: the factory
 * and executor interfaces reference only {@code java.util.concurrent.Callable} and
 * {@code AutoCloseable} from {@code java.base}.</p>
 *
 * <p>The production implementation lives outside this module (in {@code core/persistence}
 * or {@code lifecycle/lifecycle}) and creates a real platform thread + SQLite read connection
 * per subscriber. Test fixtures provide a synchronous stub.</p>
 *
 * @see SubscriberReadExecutor
 * @see EventBus#subscribeRuntime(SubscriberInfo, Subscriber)
 */
@FunctionalInterface
public interface SubscriberReadConnectionFactory {

    /**
     * Creates a subscriber-dedicated read executor. The returned executor submits
     * read operations on a dedicated platform thread with a dedicated SQLite
     * connection. The factory is called once per {@code subscribeRuntime()} call.
     *
     * @param subscriberId the subscriber requesting a read connection
     * @return a {@link SubscriberReadExecutor} for this subscriber; never {@code null}
     */
    SubscriberReadExecutor create(String subscriberId);
}
