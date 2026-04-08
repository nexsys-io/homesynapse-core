/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event.bus.test;

import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventPage;
import com.homesynapse.event.EventStore;
import com.homesynapse.event.bus.CheckpointStore;
import com.homesynapse.event.bus.EventBus;
import com.homesynapse.event.bus.SubscriberInfo;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

/**
 * Full-fidelity, in-memory {@link EventBus} implementation for contract testing.
 *
 * <p>This implementation delivers notifications synchronously with complete filter
 * evaluation. It takes an {@link EventStore} and {@link CheckpointStore} as constructor
 * parameters, matching the dependency structure of the production
 * {@code InProcessEventBus}.</p>
 *
 * <p>When {@link #notifyEvent(long)} is called, this implementation:</p>
 * <ol>
 *   <li>Loads the event at the given position from the {@code EventStore}</li>
 *   <li>Evaluates each registered subscriber's {@link com.homesynapse.event.bus.SubscriptionFilter
 *       filter} against the event envelope</li>
 *   <li>Checks that the subscriber's checkpoint position is below the notified position</li>
 *   <li>If both checks pass and the subscriber has a handler, invokes the handler
 *       synchronously with the global position</li>
 * </ol>
 *
 * <p>This differs from the production {@code InProcessEventBus} (which uses
 * {@code LockSupport.unpark()} to wake virtual threads) but provides the same
 * behavioral contract for filter evaluation and checkpoint-based skip logic.</p>
 *
 * <p><strong>Thread safety:</strong> The subscriber registry is guarded by a
 * {@link ReentrantReadWriteLock} per LTD-11 (no {@code synchronized} to avoid
 * pinning virtual threads). Read lock protects {@link #notifyEvent} iteration;
 * write lock protects {@link #subscribe}, {@link #unsubscribe},
 * {@link #subscribeWithHandler}, and {@link #reset}.</p>
 *
 * @see EventBus
 * @see EventBusContractTest
 * @see CheckpointStore
 * @see EventStore
 */
public class InMemoryEventBus implements EventBus {

    private final EventStore eventStore;
    private final CheckpointStore checkpointStore;
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

    /**
     * Maps subscriberId to its registration. Handler is nullable — null for
     * standard {@link #subscribe} registrations (production pattern where the
     * bus wakes the subscriber's thread, not a callback).
     */
    private final ConcurrentHashMap<String, SubscriberRegistration> registry =
            new ConcurrentHashMap<>();

    /**
     * Creates a new in-memory event bus backed by the given stores.
     *
     * @param eventStore      the event store for loading event metadata during
     *                        filter evaluation in {@link #notifyEvent}; never {@code null}
     * @param checkpointStore the checkpoint store for subscriber position tracking;
     *                        never {@code null}
     * @throws NullPointerException if either parameter is {@code null}
     */
    public InMemoryEventBus(EventStore eventStore, CheckpointStore checkpointStore) {
        this.eventStore = Objects.requireNonNull(eventStore,
                "eventStore must not be null");
        this.checkpointStore = Objects.requireNonNull(checkpointStore,
                "checkpointStore must not be null");
    }

    @Override
    public void subscribe(SubscriberInfo subscriber) {
        Objects.requireNonNull(subscriber, "subscriber must not be null");
        rwLock.writeLock().lock();
        try {
            registry.put(subscriber.subscriberId(),
                    new SubscriberRegistration(subscriber, null));
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    @Override
    public void unsubscribe(String subscriberId) {
        Objects.requireNonNull(subscriberId, "subscriberId must not be null");
        rwLock.writeLock().lock();
        try {
            registry.remove(subscriberId);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    @Override
    public void notifyEvent(long globalPosition) {
        // Load the event at globalPosition from the store.
        // readFrom(afterPosition, maxCount) returns events with globalPosition > afterPosition,
        // so pass (globalPosition - 1) to get the event AT globalPosition.
        EventPage page = eventStore.readFrom(globalPosition - 1, 1);
        if (page.events().isEmpty()) {
            return;
        }
        EventEnvelope envelope = page.events().get(0);

        rwLock.readLock().lock();
        try {
            for (SubscriberRegistration reg : registry.values()) {
                // Evaluate filter
                if (!reg.info().filter().matches(envelope)) {
                    continue;
                }

                // Check checkpoint — skip if subscriber already processed this position
                long checkpoint = checkpointStore.readCheckpoint(
                        reg.info().subscriberId());
                if (checkpoint >= globalPosition) {
                    continue;
                }

                // Invoke handler if present (test-fixture callback bridge)
                if (reg.handler() != null) {
                    reg.handler().accept(globalPosition);
                }
            }
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public long subscriberPosition(String subscriberId) {
        Objects.requireNonNull(subscriberId, "subscriberId must not be null");
        return checkpointStore.readCheckpoint(subscriberId);
    }

    /**
     * Registers a subscriber with a notification callback.
     *
     * <p>When {@link #notifyEvent(long)} matches this subscriber's filter and the
     * subscriber's checkpoint is below the notified position, the handler is invoked
     * synchronously with the global position. This is the test-fixture callback
     * bridge — production implementations use {@code LockSupport.unpark()} instead.</p>
     *
     * @param info    the subscriber registration metadata; never {@code null}
     * @param handler callback invoked with the global position on matching notification;
     *                never {@code null}
     * @throws NullPointerException if either parameter is {@code null}
     */
    public void subscribeWithHandler(SubscriberInfo info, Consumer<Long> handler) {
        Objects.requireNonNull(info, "info must not be null");
        Objects.requireNonNull(handler, "handler must not be null");
        rwLock.writeLock().lock();
        try {
            registry.put(info.subscriberId(),
                    new SubscriberRegistration(info, handler));
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Clears all subscriber registrations, resetting to an empty state.
     *
     * <p>Intended to be called between tests for isolation via the contract
     * test's {@code resetAll()} method. Does NOT clear the backing
     * {@link CheckpointStore} — that is the caller's responsibility.</p>
     */
    public void reset() {
        rwLock.writeLock().lock();
        try {
            registry.clear();
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Internal registration record pairing subscriber metadata with an optional
     * notification callback.
     *
     * @param info    the subscriber registration metadata
     * @param handler the notification callback, or {@code null} for standard
     *                (non-callback) registrations
     */
    private record SubscriberRegistration(
            SubscriberInfo info,
            Consumer<Long> handler
    ) {}
}
