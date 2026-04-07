/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.test;

import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventPage;
import com.homesynapse.event.EventStore;
import com.homesynapse.event.bus.SubscriberInfo;
import com.homesynapse.event.bus.SubscriptionFilter;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Configurable event bus subscriber for testing.
 *
 * <p>Registers with {@link SynchronousEventBus} (or any event bus via
 * {@link #registerWith(SynchronousEventBus)}), pulls events from an
 * {@link EventStore} when notified, and collects them into an
 * {@link EventCollector}. Supports configurable processing delay and
 * failure injection for testing error handling and slow-subscriber
 * scenarios.</p>
 *
 * <h3>Pull-Based Handler Pattern</h3>
 *
 * <p>This mirrors the production subscriber pattern: wake → pull from store →
 * filter → process → advance checkpoint. The handler registered via
 * {@link #registerWith(SynchronousEventBus)} uses
 * {@link SynchronousEventBus#registerHandler(String, SubscriptionFilter,
 * java.util.function.LongConsumer)} (not {@code subscribe()}) because
 * that is the path that actually receives callbacks on
 * {@link com.homesynapse.event.bus.EventBus#notifyEvent(long)}.</p>
 *
 * <h3>Processing Delay</h3>
 *
 * <p>{@link #withProcessingDelay(Duration)} uses {@code Thread.sleep()}
 * intentionally to simulate slow subscriber processing in tests. This is a
 * test-only facility — {@code Thread.sleep()} is prohibited in production
 * code.</p>
 *
 * @see EventCollector
 * @see SynchronousEventBus
 * @see EventStore
 */
public final class TestSubscriber {

    private static final int BATCH_SIZE = 100;

    private final String subscriberId;
    private final EventStore store;
    private final SubscriptionFilter filter;
    private final EventCollector collector = new EventCollector();
    private final AtomicInteger processedCount = new AtomicInteger(0);

    private boolean coalesceExempt;
    private long checkpoint;
    private Duration delay;
    private FailOnNth failOnNth;

    /**
     * Record capturing the failure injection configuration.
     */
    private record FailOnNth(int n, RuntimeException exception) {

        /** Creates a new failure injection config. */
        FailOnNth {
            Objects.requireNonNull(exception, "exception must not be null");
        }
    }

    /**
     * Creates a new {@code TestSubscriber} with the given subscriber ID,
     * event store, and subscription filter.
     *
     * @param subscriberId the unique subscriber identifier; never {@code null}
     * @param store        the event store to pull events from; never {@code null}
     * @param filter       the subscription filter; never {@code null}
     */
    public TestSubscriber(String subscriberId, EventStore store,
                          SubscriptionFilter filter) {
        this.subscriberId = Objects.requireNonNull(subscriberId,
                "subscriberId must not be null");
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.filter = Objects.requireNonNull(filter, "filter must not be null");
    }

    // ──────────────────────────────────────────────────────────────────
    // Static factory methods
    // ──────────────────────────────────────────────────────────────────

    /**
     * Creates a {@code TestSubscriber} with the default filter (all events).
     *
     * @param subscriberId the unique subscriber identifier; never {@code null}
     * @param store        the event store to pull events from; never {@code null}
     * @return a new TestSubscriber
     */
    public static TestSubscriber create(String subscriberId, EventStore store) {
        return new TestSubscriber(subscriberId, store, SubscriptionFilter.all());
    }

    /**
     * Creates a {@code TestSubscriber} with a specific subscription filter.
     *
     * @param subscriberId the unique subscriber identifier; never {@code null}
     * @param store        the event store to pull events from; never {@code null}
     * @param filter       the subscription filter; never {@code null}
     * @return a new TestSubscriber
     */
    public static TestSubscriber create(String subscriberId, EventStore store,
                                        SubscriptionFilter filter) {
        return new TestSubscriber(subscriberId, store, filter);
    }

    // ──────────────────────────────────────────────────────────────────
    // Accessors
    // ──────────────────────────────────────────────────────────────────

    /**
     * Returns the subscriber identifier.
     *
     * @return the subscriber ID
     */
    public String subscriberId() {
        return subscriberId;
    }

    /**
     * Returns the {@link SubscriberInfo} descriptor for this subscriber.
     *
     * @return the subscriber info record
     */
    public SubscriberInfo subscriberInfo() {
        return new SubscriberInfo(subscriberId, filter, coalesceExempt);
    }

    /**
     * Returns the backing {@link EventCollector} that accumulates processed events.
     *
     * @return the event collector
     */
    public EventCollector collector() {
        return collector;
    }

    /**
     * Returns the current checkpoint position (the global position up to which
     * events have been processed).
     *
     * @return the checkpoint position
     */
    public long checkpoint() {
        return checkpoint;
    }

    /**
     * Returns the total number of events processed by this subscriber.
     *
     * @return the processed event count
     */
    public int processedCount() {
        return processedCount.get();
    }

    // ──────────────────────────────────────────────────────────────────
    // Registration
    // ──────────────────────────────────────────────────────────────────

    /**
     * Registers this subscriber with a {@link SynchronousEventBus}.
     *
     * <p>Uses {@link SynchronousEventBus#registerHandler(String,
     * SubscriptionFilter, java.util.function.LongConsumer)} to receive
     * synchronous callbacks on {@code notifyEvent()}. The handler pulls
     * events from the store, filters them, and collects matching events.</p>
     *
     * @param bus the synchronous event bus to register with; never {@code null}
     */
    public void registerWith(SynchronousEventBus bus) {
        Objects.requireNonNull(bus, "bus must not be null");
        bus.registerHandler(subscriberId, filter, this::onNotify);
    }

    // ──────────────────────────────────────────────────────────────────
    // Fluent configuration
    // ──────────────────────────────────────────────────────────────────

    /**
     * Sets whether this subscriber is coalescing-exempt.
     *
     * @param exempt {@code true} to exempt from coalescing
     * @return this subscriber for chaining
     */
    public TestSubscriber withCoalesceExempt(boolean exempt) {
        this.coalesceExempt = exempt;
        return this;
    }

    /**
     * Configures a processing delay between events.
     *
     * <p><strong>Uses {@code Thread.sleep()} intentionally</strong> to simulate
     * slow subscriber processing in tests. This is a test-only facility —
     * {@code Thread.sleep()} is prohibited in production code.</p>
     *
     * @param delay the delay to apply between processing each event;
     *              never {@code null}
     * @return this subscriber for chaining
     */
    public TestSubscriber withProcessingDelay(Duration delay) {
        this.delay = Objects.requireNonNull(delay, "delay must not be null");
        return this;
    }

    /**
     * Configures the subscriber to throw the given exception after processing
     * the Nth event (cumulative across all notifications).
     *
     * @param n the event number at which to throw (1-based)
     * @param e the exception to throw
     * @return this subscriber for chaining
     */
    public TestSubscriber withFailOnNthEvent(int n, RuntimeException e) {
        this.failOnNth = new FailOnNth(n, e);
        return this;
    }

    // ──────────────────────────────────────────────────────────────────
    // Reset
    // ──────────────────────────────────────────────────────────────────

    /**
     * Clears the collector, resets the checkpoint to 0, and resets
     * the processed count.
     */
    public void reset() {
        collector.clear();
        checkpoint = 0;
        processedCount.set(0);
    }

    @Override
    public String toString() {
        return "TestSubscriber[id=" + subscriberId
                + ", processed=" + processedCount.get()
                + ", checkpoint=" + checkpoint + "]";
    }

    // ──────────────────────────────────────────────────────────────────
    // Internal handler
    // ──────────────────────────────────────────────────────────────────

    /**
     * Pull-based notification handler: reads events from the store starting
     * at the current checkpoint, filters by the subscription filter, and
     * collects matching events.
     */
    private void onNotify(long globalPosition) {
        EventPage page = store.readFrom(checkpoint, BATCH_SIZE);
        for (EventEnvelope event : page.events()) {
            if (filter.matches(event)) {
                if (delay != null) {
                    try {
                        Thread.sleep(delay.toMillis());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                int count = processedCount.incrementAndGet();
                if (failOnNth != null && count == failOnNth.n()) {
                    throw failOnNth.exception();
                }
                collector.add(event);
            }
        }
        checkpoint = page.nextPosition();
    }
}
