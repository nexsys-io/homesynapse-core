/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.test;

import com.homesynapse.event.bus.EventBus;
import com.homesynapse.event.bus.SubscriberInfo;
import com.homesynapse.event.bus.SubscriptionFilter;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongConsumer;

/**
 * A synchronous, single-threaded {@link EventBus} for deterministic testing.
 *
 * <p>In production, the {@code InProcessEventBus} notifies subscribers via
 * {@code LockSupport.unpark()} and they process events on dedicated virtual
 * threads. In tests, this implementation calls subscriber handlers <b>inline</b>
 * during {@link #notifyEvent(long)}, ensuring:
 * <ul>
 *   <li>Deterministic ordering — subscribers process in registration order</li>
 *   <li>No async dispatch — no {@code Thread.sleep()} or polling needed</li>
 *   <li>Immediate visibility — all side effects visible after notifyEvent returns</li>
 * </ul>
 *
 * <p>Since the production {@link EventBus} is pull-based (subscribers poll the
 * {@code EventStore} after being woken), this test implementation bridges the gap
 * by accepting {@link LongConsumer} handlers via
 * {@link #registerHandler(String, SubscriptionFilter, LongConsumer)} that are
 * invoked synchronously when {@link #notifyEvent(long)} is called. Subscribers
 * registered via {@link #subscribe(SubscriberInfo)} are tracked for position
 * queries but do not receive callback notifications (use {@code registerHandler}
 * for callback-driven testing).
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * var bus = new SynchronousEventBus();
 * bus.registerHandler("state-projection", SubscriptionFilter.all(), position -> {
 *     // process events from the in-memory event store starting at position
 * });
 * bus.notifyEvent(1L); // handler called synchronously
 * }</pre>
 *
 * @see EventBus
 */
public final class SynchronousEventBus implements EventBus {

    /** Creates a new empty {@code SynchronousEventBus}. */
    public SynchronousEventBus() {}

    private record RegisteredHandler(
            String subscriberId,
            SubscriptionFilter filter,
            LongConsumer handler
    ) {}

    private final ConcurrentHashMap<String, SubscriberInfo> subscribers = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<RegisteredHandler> handlers = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, Long> checkpoints = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Long> notifiedPositions = new CopyOnWriteArrayList<>();

    /**
     * Registers a subscriber that will be called synchronously on
     * {@link #notifyEvent(long)}.
     *
     * <p>This is a <b>test-only convenience method</b> not present on the
     * production EventBus interface. It provides a simple callback-based
     * subscription for tests that don't need the full subscriber lifecycle.
     *
     * @param subscriberId unique subscriber identifier
     * @param filter       subscription filter (stored for documentation; the synchronous
     *                     bus invokes all handlers regardless of filter)
     * @param handler      callback receiving the global position
     */
    public void registerHandler(String subscriberId, SubscriptionFilter filter, LongConsumer handler) {
        Objects.requireNonNull(subscriberId, "subscriberId");
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(handler, "handler");
        handlers.add(new RegisteredHandler(subscriberId, filter, handler));
    }

    @Override
    public void subscribe(SubscriberInfo subscriber) {
        Objects.requireNonNull(subscriber, "subscriber");
        subscribers.put(subscriber.subscriberId(), subscriber);
    }

    @Override
    public void unsubscribe(String subscriberId) {
        Objects.requireNonNull(subscriberId, "subscriberId");
        subscribers.remove(subscriberId);
        handlers.removeIf(h -> h.subscriberId().equals(subscriberId));
    }

    /**
     * Notifies all registered handlers that events are available at the given position.
     *
     * <p>Unlike the production EventBus which uses {@code LockSupport.unpark()},
     * this implementation calls each handler <b>synchronously</b> in registration order.
     *
     * @param globalPosition the position of the newly available event(s)
     */
    @Override
    public void notifyEvent(long globalPosition) {
        notifiedPositions.add(globalPosition);
        for (var handler : handlers) {
            handler.handler().accept(globalPosition);
        }
    }

    @Override
    public long subscriberPosition(String subscriberId) {
        Objects.requireNonNull(subscriberId, "subscriberId");
        return checkpoints.getOrDefault(subscriberId, 0L);
    }

    /**
     * Records a checkpoint position for the given subscriber.
     * Test-only convenience for simulating checkpoint writes without a
     * {@link com.homesynapse.event.bus.CheckpointStore CheckpointStore}.
     *
     * @param subscriberId   the subscriber identifier
     * @param globalPosition the position to record
     */
    public void setCheckpoint(String subscriberId, long globalPosition) {
        Objects.requireNonNull(subscriberId, "subscriberId");
        checkpoints.put(subscriberId, globalPosition);
    }

    /**
     * Returns all positions that were notified, in order.
     * Useful for test assertions.
     *
     * @return unmodifiable list of notified positions
     */
    public List<Long> notifiedPositions() {
        return List.copyOf(notifiedPositions);
    }

    /**
     * Returns the number of registered subscribers (both interface-based and
     * handler-based).
     *
     * @return subscriber count
     */
    public int subscriberCount() {
        return subscribers.size() + handlers.size();
    }

    /**
     * Returns an unmodifiable snapshot of the subscriber registry.
     * Useful for test assertions that verify subscriber registration.
     *
     * @return unmodifiable map of subscriberId to SubscriberInfo
     */
    public Map<String, SubscriberInfo> registeredSubscribers() {
        return Map.copyOf(subscribers);
    }

    /**
     * Clears all registered subscribers, handlers, checkpoints, and notification
     * history. Call between tests for isolation.
     */
    public void reset() {
        subscribers.clear();
        handlers.clear();
        checkpoints.clear();
        notifiedPositions.clear();
    }

    @Override
    public String toString() {
        return "SynchronousEventBus[subscribers=" + subscribers.size()
                + ", handlers=" + handlers.size()
                + ", notified=" + notifiedPositions.size() + "]";
    }
}
