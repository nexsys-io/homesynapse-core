/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event.bus;

import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventPage;
import com.homesynapse.event.EventStore;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

/**
 * Production {@link EventBus} implementation (AMD-42, PLAN-M3-CONSOLIDATED-02 §4).
 *
 * <p>Manages the subscriber registry, mode FSM, per-subscriber runtime lifecycle,
 * supervisor with DLQ, and circuit breaker. Supports both passive subscribers
 * (registered via {@link #subscribe(SubscriberInfo)}) and active runtime subscribers
 * (registered via {@link #subscribeRuntime(SubscriberInfo, Subscriber)}).</p>
 *
 * <p>Passive subscribers are notified via an optional callback bridge (for backward
 * compatibility with the Phase 2 contract test). Active subscribers receive events
 * through their dedicated virtual thread's pull loop.</p>
 *
 * <p><strong>Thread safety:</strong> The subscriber registry is guarded by a
 * {@link ReentrantReadWriteLock} per LTD-11 (no {@code synchronized}).
 * Notification fan-out acquires the read lock; registry mutations acquire the
 * write lock.</p>
 *
 * @see EventBus
 * @see SubscriberRuntime
 * @see SubscriberSupervisor
 */
final class InProcessEventBus implements EventBus {

    private final EventStore eventStore;
    private final CheckpointStore checkpointStore;
    private final Clock clock;
    private final SubscriberReadConnectionFactory readConnectionFactory;
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

    /**
     * Passive subscriber registrations (subscribe() path — filter + optional callback).
     */
    private final ConcurrentHashMap<String, PassiveRegistration> passiveRegistry =
            new ConcurrentHashMap<>();

    /**
     * Active subscriber runtimes (subscribeRuntime() path — full VT + supervisor).
     */
    private final ConcurrentHashMap<String, SubscriberRuntime> activeRegistry =
            new ConcurrentHashMap<>();

    /**
     * Creates a new in-process event bus.
     *
     * @param eventStore            the event store for loading event metadata
     * @param checkpointStore       the checkpoint store for position tracking
     * @param clock                 the clock for supervisor timing (never {@code null})
     * @param readConnectionFactory factory for per-subscriber read executors
     * @throws NullPointerException if any parameter is {@code null}
     */
    InProcessEventBus(EventStore eventStore,
                      CheckpointStore checkpointStore,
                      Clock clock,
                      SubscriberReadConnectionFactory readConnectionFactory) {
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore must not be null");
        this.checkpointStore = Objects.requireNonNull(checkpointStore,
                "checkpointStore must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.readConnectionFactory = Objects.requireNonNull(readConnectionFactory,
                "readConnectionFactory must not be null");
    }

    // ── Existing Phase 2 contract (passive registration) ─────────────

    @Override
    public void subscribe(SubscriberInfo subscriber) {
        Objects.requireNonNull(subscriber, "subscriber must not be null");
        rwLock.writeLock().lock();
        try {
            passiveRegistry.put(subscriber.subscriberId(),
                    new PassiveRegistration(subscriber, null));
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    @Override
    public void unsubscribe(String subscriberId) {
        Objects.requireNonNull(subscriberId, "subscriberId must not be null");
        rwLock.writeLock().lock();
        try {
            passiveRegistry.remove(subscriberId);
            SubscriberRuntime runtime = activeRegistry.remove(subscriberId);
            if (runtime != null) {
                runtime.close();
            }
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    @Override
    public void notifyEvent(long globalPosition) {
        // Load the event at globalPosition from the store for filter evaluation.
        EventPage page = eventStore.readFrom(globalPosition - 1, 1);
        if (page.events().isEmpty()) {
            return;
        }
        EventEnvelope envelope = page.events().get(0);

        rwLock.readLock().lock();
        try {
            // Notify passive subscribers (callback bridge)
            for (PassiveRegistration reg : passiveRegistry.values()) {
                if (!reg.info().filter().matches(envelope)) {
                    continue;
                }
                long checkpoint = checkpointStore.readCheckpoint(reg.info().subscriberId());
                if (checkpoint >= globalPosition) {
                    continue;
                }
                if (reg.handler() != null) {
                    reg.handler().accept(globalPosition);
                }
            }

            // Notify active subscribers — enqueue position and unpark their VT
            for (SubscriberRuntime runtime : activeRegistry.values()) {
                if (!runtime.info().filter().matches(envelope)) {
                    continue;
                }
                long checkpoint = checkpointStore.readCheckpoint(
                        runtime.info().subscriberId());
                if (checkpoint >= globalPosition) {
                    continue;
                }

                SubscriberMode currentMode = runtime.mode();
                if (currentMode == SubscriberMode.SUSPENDED
                        || currentMode == SubscriberMode.COLD) {
                    continue;
                }

                // Enqueue position for the subscriber's VT to process
                runtime.pendingPositions().offer(globalPosition);
                Thread vt = runtime.virtualThread();
                if (vt != null) {
                    LockSupport.unpark(vt);
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

    // ── New in M3.1 (AMD-42 lifecycle introspection and active runtime) ─

    @Override
    public void subscribeRuntime(SubscriberInfo info, Subscriber runtime) {
        Objects.requireNonNull(info, "info must not be null");
        Objects.requireNonNull(runtime, "runtime must not be null");

        SubscriberReadExecutor readExecutor = readConnectionFactory.create(info.subscriberId());
        SubscriberDlq dlq = new SubscriberDlq();
        SubscriberSupervisor supervisor = new SubscriberSupervisor(
                info.subscriberId(), clock, dlq);
        ReplayWindowQueue replayWindowQueue = new ReplayWindowQueue();

        SubscriberRuntime subscriberRuntime = new SubscriberRuntime(
                info, runtime, readExecutor, supervisor, dlq, replayWindowQueue);

        rwLock.writeLock().lock();
        try {
            // Remove any existing passive registration for the same ID
            passiveRegistry.remove(info.subscriberId());
            // Close any existing active runtime for the same ID
            SubscriberRuntime existing = activeRegistry.put(
                    info.subscriberId(), subscriberRuntime);
            if (existing != null) {
                existing.close();
            }
        } finally {
            rwLock.writeLock().unlock();
        }

        // Start the subscriber's virtual thread
        Thread vt = Thread.ofVirtual()
                .name("hs-sub-" + info.subscriberId())
                .start(() -> subscriberLoop(subscriberRuntime));
        subscriberRuntime.setVirtualThread(vt);
    }

    @Override
    public void resume(String subscriberId) {
        Objects.requireNonNull(subscriberId, "subscriberId must not be null");
        SubscriberRuntime runtime = activeRegistry.get(subscriberId);
        if (runtime == null) {
            throw new IllegalStateException(
                    "No active subscriber with ID: " + subscriberId);
        }
        if (runtime.mode() != SubscriberMode.SUSPENDED) {
            throw new IllegalStateException(
                    "Subscriber '" + subscriberId + "' is not in SUSPENDED mode, "
                            + "current mode: " + runtime.mode());
        }

        // Clear crash window and DLQ
        runtime.supervisor().clearCrashWindow();
        runtime.dlq().clear();

        // Transition SUSPENDED → REPLAY (re-bootstrap from last checkpoint)
        runtime.transitionTo(SubscriberMode.REPLAY);
    }

    @Override
    public SubscriberSnapshot subscriberInfo(String subscriberId) {
        Objects.requireNonNull(subscriberId, "subscriberId must not be null");
        SubscriberRuntime runtime = activeRegistry.get(subscriberId);
        if (runtime == null) {
            throw new IllegalArgumentException(
                    "No subscriber with ID: " + subscriberId);
        }
        return buildSnapshot(runtime);
    }

    @Override
    public List<SubscriberSnapshot> subscribers() {
        List<SubscriberSnapshot> snapshots = new ArrayList<>();
        for (SubscriberRuntime runtime : activeRegistry.values()) {
            snapshots.add(buildSnapshot(runtime));
        }
        return List.copyOf(snapshots);
    }

    // ── Internal helpers ─────────────────────────────────────────────

    /**
     * The subscriber's virtual thread loop. Transitions from COLD to REPLAY
     * on first scheduling. Processes events from the pending positions queue.
     * Full REPLAY→LIVE transition logic is M3.2 scope.
     *
     * @param runtime the subscriber runtime bundle
     */
    private void subscriberLoop(SubscriberRuntime runtime) {
        // Transition COLD → REPLAY on first scheduling
        runtime.compareAndTransition(SubscriberMode.COLD, SubscriberMode.REPLAY);

        // M3.1 event processing loop: pull positions from queue, load and deliver.
        // M3.2 will implement the full replay-from-checkpoint, transition drain,
        // and LIVE steady-state loop. For M3.1, this loop serves both REPLAY and
        // LIVE delivery needs.
        while (!Thread.currentThread().isInterrupted()) {
            Long position = runtime.pendingPositions().poll();
            if (position == null) {
                // No work — park until notifyEvent unparks us
                LockSupport.park();
                continue;
            }

            // Skip if suspended (circuit breaker may have tripped during processing)
            if (runtime.mode() == SubscriberMode.SUSPENDED) {
                continue;
            }

            // Load the event and deliver via supervisor
            EventPage page = eventStore.readFrom(position - 1, 1);
            if (!page.events().isEmpty()) {
                EventEnvelope envelope = page.events().get(0);
                runtime.supervisor().deliver(
                        runtime.subscriber(), envelope, runtime);
            }
        }
    }

    /**
     * Builds a snapshot from a subscriber runtime.
     *
     * @param runtime the runtime to snapshot
     * @return the point-in-time snapshot
     */
    private SubscriberSnapshot buildSnapshot(SubscriberRuntime runtime) {
        return new SubscriberSnapshot(
                runtime.info().subscriberId(),
                runtime.mode(),
                checkpointStore.readCheckpoint(runtime.info().subscriberId()),
                runtime.dlq().depth(),
                runtime.supervisor().crashCount()
        );
    }

    /**
     * Registers a passive subscriber with a notification callback.
     *
     * <p>This is the callback bridge for backward compatibility with Phase 2
     * contract tests. Production subscribers use {@link #subscribeRuntime}.</p>
     *
     * @param info    the subscriber registration metadata
     * @param handler callback invoked with the global position on match
     */
    void subscribeWithHandler(SubscriberInfo info, Consumer<Long> handler) {
        Objects.requireNonNull(info, "info must not be null");
        Objects.requireNonNull(handler, "handler must not be null");
        rwLock.writeLock().lock();
        try {
            passiveRegistry.put(info.subscriberId(),
                    new PassiveRegistration(info, handler));
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Resets the bus to empty state. For test isolation only.
     */
    void reset() {
        rwLock.writeLock().lock();
        try {
            passiveRegistry.clear();
            for (SubscriberRuntime runtime : activeRegistry.values()) {
                runtime.close();
            }
            activeRegistry.clear();
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Internal passive registration record pairing subscriber metadata with an
     * optional notification callback.
     *
     * @param info    the subscriber registration metadata
     * @param handler the notification callback, or {@code null} for standard registrations
     */
    private record PassiveRegistration(
            SubscriberInfo info,
            Consumer<Long> handler
    ) {}
}
