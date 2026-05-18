/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event.bus;

import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventPage;
import com.homesynapse.event.EventStore;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * Production {@link EventBus} implementation (AMD-42, PLAN-M3-CONSOLIDATED-02 §4, §6).
 *
 * <p>Manages the subscriber registry, mode FSM, per-subscriber runtime lifecycle,
 * supervisor with DLQ, and circuit breaker. Supports both passive subscribers
 * (registered via {@link #subscribe(SubscriberInfo)}) and active runtime subscribers
 * (registered via {@link #subscribeRuntime(SubscriberInfo, Subscriber)}).</p>
 *
 * <p>Active subscribers execute the three-phase
 * {@link SubscriberMode#COLD COLD} → {@link SubscriberMode#REPLAY REPLAY}
 * → {@link SubscriberMode#TRANSITION TRANSITION} → {@link SubscriberMode#LIVE LIVE}
 * algorithm: {@link ReplayDriver} pages through the event log from the persisted
 * checkpoint, {@link TransitionCoordinator} drains the
 * {@link ReplayWindowQueue} with gap detection, and the LIVE pull loop
 * processes notifications dispatched by {@link #notifyEvent(long)}.</p>
 *
 * <p><strong>Thread safety:</strong> The subscriber registry is guarded by a
 * {@link ReentrantReadWriteLock} per LTD-11 (no {@code synchronized}).
 * Notification fan-out acquires the read lock; registry mutations acquire the
 * write lock. Per-subscriber state is guarded by per-runtime structures
 * ({@link ReplayWindowQueue}'s internal lock, {@code AtomicReference<SubscriberMode>},
 * and the supervisor's single-VT invariant).</p>
 *
 * @see EventBus
 * @see SubscriberRuntime
 * @see SubscriberSupervisor
 * @see ReplayDriver
 * @see TransitionCoordinator
 */
final class InProcessEventBus implements EventBus {

    /** AMD-43 §3.6.2 threshold above which the publisher-blocked counter increments. */
    static final int PUBLISHER_BLOCKED_DEPTH_THRESHOLD = 5000;

    private final EventStore eventStore;
    private final CheckpointStore checkpointStore;
    private final Clock clock;
    private final SubscriberReadConnectionFactory readConnectionFactory;
    private final BusMetrics metrics;
    private final IntSupplier queueDepthSupplier;
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
     * Creates a new in-process event bus with no-op metrics and a zero queue-depth
     * supplier. Convenience constructor for tests that don't exercise M3.3 paths.
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
        this(eventStore, checkpointStore, clock, readConnectionFactory,
                BusMetrics.noop(), () -> 0);
    }

    /**
     * Creates a new in-process event bus.
     *
     * @param eventStore            the event store for loading event metadata
     * @param checkpointStore       the checkpoint store for position tracking
     * @param clock                 the clock for supervisor timing (never {@code null})
     * @param readConnectionFactory factory for per-subscriber read executors
     * @param metrics               the bus metrics emitter (AMD-43 §3.6.2)
     * @param queueDepthSupplier    supplier of the writer queue depth (DEC-M3-14 —
     *                              the bus holds no reference to persistence types)
     * @throws NullPointerException if any parameter is {@code null}
     */
    InProcessEventBus(EventStore eventStore,
                      CheckpointStore checkpointStore,
                      Clock clock,
                      SubscriberReadConnectionFactory readConnectionFactory,
                      BusMetrics metrics,
                      IntSupplier queueDepthSupplier) {
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore must not be null");
        this.checkpointStore = Objects.requireNonNull(checkpointStore,
                "checkpointStore must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.readConnectionFactory = Objects.requireNonNull(readConnectionFactory,
                "readConnectionFactory must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
        this.queueDepthSupplier = Objects.requireNonNull(queueDepthSupplier,
                "queueDepthSupplier must not be null");
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
        // M3.3: sample writer queue depth at notification entry. The supplier is
        // injected (DEC-M3-14) so the bus holds no reference to persistence types.
        // Per INV-BUS-02, this is a record-only observation — the publisher does
        // NOT block on depth.
        Instant notifyStart = clock.instant();
        int depth = queueDepthSupplier.getAsInt();
        metrics.recordWriterQueueDepth(depth);
        if (depth > PUBLISHER_BLOCKED_DEPTH_THRESHOLD) {
            metrics.incrementPublisherBlocked();
        }

        try {
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

            // Notify active subscribers — route based on mode.
            // The queue's lock is held across the mode read + routing decision
            // so the TRANSITION → LIVE CAS in TransitionCoordinator cannot
            // interleave between our observation and our enqueue/offer.
            for (SubscriberRuntime runtime : activeRegistry.values()) {
                if (!runtime.info().filter().matches(envelope)) {
                    continue;
                }
                long checkpoint = checkpointStore.readCheckpoint(
                        runtime.info().subscriberId());
                if (checkpoint >= globalPosition) {
                    continue;
                }

                ReplayWindowQueue queue = runtime.replayWindowQueue();
                queue.lock();
                try {
                    SubscriberMode mode = runtime.mode();
                    if (mode == SubscriberMode.COLD
                            || mode == SubscriberMode.SUSPENDED) {
                        continue;
                    }
                    if (mode == SubscriberMode.REPLAY
                            || mode == SubscriberMode.TRANSITION) {
                        // Buffer until coordinator drains. Overflow is recoverable —
                        // ReplayDriver observes the latched flag and restarts REPLAY
                        // from the persisted checkpoint.
                        queue.enqueue(globalPosition);
                    } else {
                        // LIVE — standard pull path.
                        runtime.pendingPositions().offer(globalPosition);
                        Thread vt = runtime.virtualThread();
                        if (vt != null) {
                            LockSupport.unpark(vt);
                        }
                    }
                } finally {
                    queue.unlock();
                }
            }
            } finally {
                rwLock.readLock().unlock();
            }
        } finally {
            // M3.3: record the bus's notification fan-out duration as the
            // bus-side publish latency contribution. EventPublisher orchestrates
            // the full publish path (persist + notify) above this layer;
            // production wiring may add end-to-end timing in a later milestone.
            metrics.recordPublishLatency(Duration.between(notifyStart, clock.instant()));
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

        // Start the subscriber's virtual thread (INV-SUB-ISO-01).
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
     * The subscriber's virtual thread entry point. Drives the three-phase
     * transition algorithm:
     *
     * <ol>
     *   <li>{@link ReplayDriver#run()} — pages through the log from the persisted
     *       checkpoint to the live tail; CASes mode REPLAY → TRANSITION on tail
     *       reach.</li>
     *   <li>{@link TransitionCoordinator#drainAndPromote()} — drains the
     *       {@link ReplayWindowQueue} with gap detection; CASes mode TRANSITION
     *       → LIVE; fires {@code onCaughtUp()} exactly once.</li>
     *   <li>{@link #liveLoop} — steady-state LIVE delivery driven by
     *       {@code notifyEvent} via the pending-positions queue and
     *       {@code LockSupport.unpark()}.</li>
     * </ol>
     *
     * <p>Any phase returning {@code false} (circuit-breaker trip, interrupt,
     * infrastructure failure) terminates the VT — the subscriber is left in
     * {@link SubscriberMode#SUSPENDED SUSPENDED} for operator action via
     * {@link #resume(String)}.</p>
     *
     * @param runtime the subscriber runtime bundle
     */
    private void subscriberLoop(SubscriberRuntime runtime) {
        ReplayDriver driver = new ReplayDriver(runtime, eventStore, checkpointStore, clock);
        if (!driver.run()) {
            return;
        }

        TransitionCoordinator coordinator = new TransitionCoordinator(
                runtime, eventStore, clock);
        if (!coordinator.drainAndPromote()) {
            return;
        }

        liveLoop(runtime);
    }

    /**
     * Steady-state LIVE delivery loop. Polls the subscriber's pending-positions
     * queue (populated by {@link #notifyEvent(long)}), loads each event through
     * the dedicated {@link SubscriberReadExecutor}, and delivers via the
     * supervisor. Writes a per-event checkpoint after each successful delivery.
     *
     * <p>The loop exits cleanly on thread interrupt or {@code SUSPENDED} mode.</p>
     *
     * @param runtime the subscriber's runtime bundle
     */
    private void liveLoop(SubscriberRuntime runtime) {
        String subscriberId = runtime.info().subscriberId();
        SubscriptionFilter filter = runtime.info().filter();

        while (!Thread.currentThread().isInterrupted()) {
            if (runtime.mode() == SubscriberMode.SUSPENDED) {
                return;
            }

            Long position = runtime.pendingPositions().poll();
            if (position == null) {
                LockSupport.park();
                continue;
            }

            EventEnvelope envelope;
            try {
                final long pos = position;
                EventPage page = runtime.readExecutor().executeRead(
                        () -> eventStore.readFrom(pos - 1, 1));
                if (page.events().isEmpty()) {
                    continue;
                }
                envelope = page.events().get(0);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                // Transient read failure — skip this position; retain mode.
                continue;
            }

            if (!filter.matches(envelope)) {
                continue;
            }

            SubscriberSupervisor.DeliveryResult result =
                    runtime.supervisor().deliver(
                            runtime.subscriber(), envelope, runtime);
            if (result == SubscriberSupervisor.DeliveryResult.SUCCESS) {
                checkpointStore.writeCheckpoint(subscriberId, envelope.globalPosition());
                // M3.3 (AMD-43 §3.6.2): record subscriber lag after delivery.
                // lagEvents — the count of further enqueued positions ahead of
                // this delivery in the subscriber's pending queue (approximates
                // the distance to the writer tail without an extra store query).
                // lagMillis — wall-clock between event ingestion and observation.
                long lagEvents = runtime.pendingPositions().size();
                Duration lagMillis = Duration.between(envelope.ingestTime(),
                        clock.instant());
                if (lagMillis.isNegative()) {
                    lagMillis = Duration.ZERO;
                }
                metrics.recordSubscriberLag(subscriberId, lagEvents, lagMillis);
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
