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
 * checkpoint, {@link TransitionCoordinator} reads forward from the cursor to
 * the head and flips the mode, and the LIVE loop keeps reading forward, woken
 * by {@link #notifyEvent(long)}'s hint or the idle tick.</p>
 *
 * <p><strong>The delivery invariant (BUS-ORDER-1, AMD-101 §2).</strong> Every
 * position whose event matches a LIVE subscriber's filter is delivered to that
 * subscriber exactly once, in position order, regardless of the order in which
 * notifications arrive. The read-forward cursor ({@link SubscriberRuntime#cursor()}
 * — the highest position delivered or filtered past in this activation) is the
 * source of truth for delivery: the LIVE loop and the TRANSITION drain page the
 * store forward from it ({@code readFrom(cursor, liveReadBatch)}) and deliver
 * each matching envelope in order through {@link #deliverStep}. The persisted
 * checkpoint is the durable floor for restart — written after delivery,
 * monotonic because it follows the cursor, and never read to decide whether to
 * offer, wake or deliver. The notification is a wake hint plus a bounded idle
 * tick ({@code liveIdleTick}): never skipped for a LIVE subscriber and never a
 * reason to skip a position; a lost wake costs one tick, never a stalled run.</p>
 *
 * <p><strong>Delivery drops are never silent (FAILCHAN-FIX-1a).</strong>
 * {@code notifyEvent}'s own unfilterable empty page ({@code NOTIFY_NOT_VISIBLE},
 * then the unfiltered wake) and a LIVE page read that throws
 * ({@code LIVE_READ_FAILED}, once per failed page; the next tick retries) emit
 * one {@link DeliveryAnomaly} through the constructor-injected
 * {@code Consumer<DeliveryAnomaly>}; the composition root routes it to the log
 * as {@code bus.delivery_anomaly}. The bus module itself stays SLF4J-free and
 * adds no eighth metric (AMD-43 §3.6.2). The FIX-1b per-position
 * retry-and-suspend is retired by the read-forward — an empty page is "caught
 * up", not a drop — and its kinds stay defined, never emitted (INV-GA-02).</p>
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
public final class InProcessEventBus implements EventBus {

    /**
     * The {@link DeliveryAnomaly#subscriberId()} of a drop observed before
     * fan-out ({@link DeliveryAnomaly.Kind#NOTIFY_NOT_VISIBLE}) — no subscriber
     * has been resolved yet, so the anomaly belongs to all of them.
     */
    static final String ANOMALY_ALL_SUBSCRIBERS = "*";

    private final EventStore eventStore;
    private final CheckpointStore checkpointStore;
    private final Clock clock;
    private final SubscriberReadConnectionFactory readConnectionFactory;
    private final BusMetrics metrics;
    private final IntSupplier queueDepthSupplier;
    private final EventBusConfig config;
    private final int publisherBlockedDepthThreshold;
    private final Consumer<DeliveryAnomaly> anomalyEmitter;
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
    private volatile boolean abandoned = false;

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
     * Creates a new in-process event bus with no-op metrics, a zero queue-depth
     * supplier, and {@link EventBusConfig#HOME_DEFAULT}. Convenience
     * constructor for tests that don't exercise M3.3 paths.
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
     * Creates a new in-process event bus with {@link EventBusConfig#HOME_DEFAULT}.
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
        this(eventStore, checkpointStore, clock, readConnectionFactory,
                metrics, queueDepthSupplier, EventBusConfig.HOME_DEFAULT);
    }

    /**
     * Creates a new in-process event bus with a caller-supplied
     * {@link EventBusConfig} (M3.6b, audit findings D1-07 and D4-09) and NO
     * delivery-anomaly emitter — every drop signal is discarded.
     *
     * <p>Retained for callers that predate FIX-1a (the test factory and the
     * bus's own convenience constructors delegate here). The composition root
     * calls the emitter-carrying constructor below so that every silent drop
     * reaches the log.</p>
     *
     * @param eventStore                 the event store for loading event metadata
     * @param checkpointStore            the checkpoint store for position tracking
     * @param clock                      the clock for supervisor timing
     *                                   (never {@code null})
     * @param readConnectionFactory      factory for per-subscriber read executors
     * @param metrics                    the bus metrics emitter (AMD-43 §3.6.2)
     * @param writerQueueDepthSupplier   supplier of the writer queue depth
     *                                   (DEC-M3-14 — the bus holds no reference
     *                                   to persistence types)
     * @param config                     bus configuration — replay-queue
     *                                   capacity and publisher-blocked depth
     *                                   threshold (M3.6b)
     * @throws NullPointerException if any parameter is {@code null}
     */
    public InProcessEventBus(EventStore eventStore,
                             CheckpointStore checkpointStore,
                             Clock clock,
                             SubscriberReadConnectionFactory readConnectionFactory,
                             BusMetrics metrics,
                             IntSupplier writerQueueDepthSupplier,
                             EventBusConfig config) {
        this(eventStore, checkpointStore, clock, readConnectionFactory,
                metrics, writerQueueDepthSupplier, config, anomaly -> { });
    }

    /**
     * Creates a new in-process event bus with a caller-supplied
     * {@link EventBusConfig} and a delivery-anomaly emitter (FAILCHAN-FIX-1a).
     *
     * <p>This is the canonical production constructor — the composition root
     * calls it directly. Every point on the LIVE and TRANSITION delivery paths
     * where a position would otherwise be dropped silently — a single-position
     * read that returns an empty page or throws, a notification whose envelope
     * the store cannot see — emits one {@link DeliveryAnomaly} through
     * {@code anomalyEmitter} BEFORE the path returns or continues. The emitter
     * is invoked on the observing thread (the publisher's for
     * {@link DeliveryAnomaly.Kind#NOTIFY_NOT_VISIBLE}, the subscriber's virtual
     * thread otherwise) and any {@link RuntimeException} it throws is
     * swallowed — an instrument must never become a failure channel.</p>
     *
     * @param eventStore                 the event store for loading event metadata
     * @param checkpointStore            the checkpoint store for position tracking
     * @param clock                      the clock for supervisor timing and
     *                                   anomaly timestamps (never {@code null})
     * @param readConnectionFactory      factory for per-subscriber read executors
     * @param metrics                    the bus metrics emitter (AMD-43 §3.6.2)
     * @param writerQueueDepthSupplier   supplier of the writer queue depth
     *                                   (DEC-M3-14 — the bus holds no reference
     *                                   to persistence types)
     * @param config                     bus configuration — replay-queue
     *                                   capacity and publisher-blocked depth
     *                                   threshold (M3.6b)
     * @param anomalyEmitter             receives one {@link DeliveryAnomaly} per
     *                                   delivery drop; never {@code null}; must
     *                                   be safe to call from any thread
     * @throws NullPointerException if any parameter is {@code null}
     */
    public InProcessEventBus(EventStore eventStore,
                             CheckpointStore checkpointStore,
                             Clock clock,
                             SubscriberReadConnectionFactory readConnectionFactory,
                             BusMetrics metrics,
                             IntSupplier writerQueueDepthSupplier,
                             EventBusConfig config,
                             Consumer<DeliveryAnomaly> anomalyEmitter) {
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore must not be null");
        this.checkpointStore = Objects.requireNonNull(checkpointStore,
                "checkpointStore must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.readConnectionFactory = Objects.requireNonNull(readConnectionFactory,
                "readConnectionFactory must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
        this.queueDepthSupplier = Objects.requireNonNull(writerQueueDepthSupplier,
                "writerQueueDepthSupplier must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.publisherBlockedDepthThreshold = config.publisherBlockedDepthThreshold();
        this.anomalyEmitter = Objects.requireNonNull(anomalyEmitter,
                "anomalyEmitter must not be null");
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

    /**
     * Abandons the event bus, releasing all OS-level resources held by
     * subscriber runtimes (virtual threads, dedicated read connections,
     * platform-thread read executors).
     *
     * <p>Each active subscriber's virtual thread is interrupted, its
     * dedicated read connection is closed, and its DLQ and replay window
     * queues are cleared. Both the active and passive registries are
     * emptied.</p>
     *
     * <p>Use for crash simulation in tests and emergency shutdown in
     * production (e.g., imminent power loss, OOM). Normal shutdown MUST use
     * {@link #unsubscribe(String)} per subscriber.</p>
     *
     * <p>Do NOT use for normal shutdown — {@link #unsubscribe(String)}
     * performs per-subscriber cleanup with proper lifecycle transitions.</p>
     *
     * <p>Idempotent. Safe to call after individual
     * {@link #unsubscribe(String)} calls or after a previous
     * {@code abandon()}.</p>
     *
     * @implNote No in-flight event delivery is drained. If the delivery
     *           thread is mid-delivery (calling {@code subscriber.onEvent}),
     *           the interrupt lands and the thread exits. The event was
     *           already persisted (INV-ES-04), so it will be replayed on
     *           restart. No checkpoint is updated.
     */
    public void abandon() {
        if (abandoned) {
            return;
        }
        rwLock.writeLock().lock();
        try {
            abandoned = true;
            for (SubscriberRuntime runtime : activeRegistry.values()) {
                runtime.close();
            }
            activeRegistry.clear();
            passiveRegistry.clear();
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
        if (depth > publisherBlockedDepthThreshold) {
            metrics.incrementPublisherBlocked();
        }

        try {
            // Load the event at globalPosition from the store for filter evaluation.
            EventPage page = eventStore.readFrom(globalPosition - 1, 1);
            if (page.events().isEmpty()) {
                // FIX-1a: without its envelope the notification cannot be
                // filtered — the first of the four silent drop points (the
                // grounding audit S4); say so. FIX-1b (DP-2): then offer the
                // position UNFILTERED to every active subscriber — the LIVE loop
                // and the TRANSITION drain filter after their own read — so a
                // publisher-side visibility miss is never a lost delivery.
                emitAnomaly(DeliveryAnomaly.Kind.NOTIFY_NOT_VISIBLE, ANOMALY_ALL_SUBSCRIBERS,
                        globalPosition, "notifyEvent: no envelope at position");
                offerUnfiltered(globalPosition);
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

            // Notify active subscribers — route based on mode. BUS-ORDER-1
            // (AMD-101 §2): no persisted-checkpoint guard on this path — the
            // notification is a wake hint that carries no delivery decision, so
            // it is never skipped (NOTIFY_SKIPPED_LIVE is unreachable and stays
            // defined as the tripwire's name). The filter still decides whether
            // to wake at all; the read-forward filters again on its own read.
            // The queue's lock is held across the mode read + routing decision
            // so the TRANSITION → LIVE CAS in TransitionCoordinator cannot
            // interleave between our observation and our enqueue/wake.
            for (SubscriberRuntime runtime : activeRegistry.values()) {
                if (!runtime.info().filter().matches(envelope)) {
                    continue;
                }
                routeByMode(runtime, globalPosition);
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
        SubscriberDlq dlq = new SubscriberDlq(
                info.subscriberId(), PersistentDlqWriter.noop(), clock);
        SubscriberSupervisor supervisor = new SubscriberSupervisor(
                info.subscriberId(), clock, dlq);
        ReplayWindowQueue replayWindowQueue =
                new ReplayWindowQueue(config.replayQueueCapacity());

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

    /**
     * BUS-ORDER-1: the subscriber's read-forward cursor — the highest global
     * position delivered or filtered past in this activation (AMD-101 §2), the
     * in-memory truth the persisted checkpoint follows. Concrete-only, like
     * {@link #abandon()}: the {@code EventBus} interface is unchanged. The
     * lifecycle census scores {@code DELIVERED(S,P) := P <= lastDelivered(S)}.
     *
     * @param subscriberId the active subscriber's id
     * @return the cursor, or {@code -1} when no active subscriber has that id
     * @throws NullPointerException if {@code subscriberId} is {@code null}
     */
    public long lastDelivered(String subscriberId) {
        Objects.requireNonNull(subscriberId, "subscriberId must not be null");
        SubscriberRuntime runtime = activeRegistry.get(subscriberId);
        return runtime == null ? -1L : runtime.cursor();
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
     *       {@link ReplayWindowQueue}'s hints and reads forward from the cursor
     *       to the head; CASes mode TRANSITION → LIVE; fires {@code onCaughtUp()}
     *       exactly once.</li>
     *   <li>{@link #liveLoop} — steady-state LIVE delivery: the read-forward
     *       from the cursor, woken by {@code notifyEvent}'s hint or the idle
     *       tick (BUS-ORDER-1).</li>
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
                runtime, eventStore, checkpointStore, clock, config.liveReadBatch());
        if (!coordinator.drainAndPromote()) {
            return;
        }

        liveLoop(runtime);
    }

    /**
     * Steady-state LIVE delivery (BUS-ORDER-1, AMD-101 §2): the read-forward.
     * On a wake ({@link SubscriberRuntime#wake}) or the idle tick the loop
     * drains its hints, pages the store forward from the subscriber's cursor
     * ({@code readFrom(cursor, liveReadBatch)} through the dedicated
     * {@link SubscriberReadExecutor}) and hands every envelope, in position
     * order, to {@link #deliverStep}; it keeps paging while pages come back
     * full and parks for {@code liveIdleTick} on an empty or short page — but
     * only when no wake hint has arrived since the drain: the hint queue is the
     * durable wake signal, the unpark permit only the accelerator (a permit that
     * lands while the thread is parked inside its own read is spent there). The
     * notification carries no delivery decision; a lost wake costs one tick.
     *
     * <p>A page read that throws emits one {@code LIVE_READ_FAILED} at
     * {@code cursor + 1} (the first position the page would have shown) and the
     * next tick retries — the FIX-1b per-position retry-and-suspend is retired
     * ({@code LIVE_READ_EMPTY} / {@code LIVE_READ_EXHAUSTED} stay defined, never
     * emitted). The loop exits on thread interrupt, on {@code SUSPENDED} mode,
     * or when a delivery SUSPENDs the subscriber.</p>
     *
     * @param runtime the subscriber's runtime bundle
     */
    private void liveLoop(SubscriberRuntime runtime) {
        String subscriberId = runtime.info().subscriberId();
        int batch = config.liveReadBatch();
        long idleTickNanos = config.liveIdleTick().toNanos();

        while (!Thread.currentThread().isInterrupted()) {
            if (runtime.mode() == SubscriberMode.SUSPENDED) {
                return;
            }
            runtime.drainHints();

            List<EventEnvelope> page;
            try {
                long from = runtime.cursor();
                page = runtime.readExecutor().executeRead(
                        () -> eventStore.readFrom(from, batch)).events();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                emitAnomaly(DeliveryAnomaly.Kind.LIVE_READ_FAILED, subscriberId,
                        runtime.cursor() + 1, e.getClass().getSimpleName() + ": " + e.getMessage());
                LockSupport.parkNanos(idleTickNanos);
                continue;
            }

            for (int i = 0; i < page.size(); i++) {
                EventEnvelope envelope = page.get(i);
                StepOutcome outcome = deliverStep(runtime, envelope, checkpointStore);
                if (outcome == StepOutcome.STOP) {
                    return;
                }
                if (outcome == StepOutcome.DELIVERED) {
                    // M3.3 (AMD-43 §3.6.2): record subscriber lag after delivery.
                    // lagEvents — what is still ahead of this delivery that the loop
                    // can count without a store query: the rest of this page plus
                    // the un-consumed wake hints (one per publish since the drain).
                    // lagMillis — wall-clock between event ingestion and observation.
                    long lagEvents = (page.size() - 1 - i) + runtime.pendingPositions().size();
                    Duration lagMillis = Duration.between(envelope.ingestTime(),
                            clock.instant());
                    if (lagMillis.isNegative()) {
                        lagMillis = Duration.ZERO;
                    }
                    metrics.recordSubscriberLag(subscriberId, lagEvents, lagMillis);
                }
            }

            if (page.size() < batch && runtime.pendingPositions().isEmpty()) {
                // Caught up as of this read AND no wake hint has arrived since the
                // drain: park until a hint or the idle tick. The hint queue, not the
                // unpark permit, is the durable wake signal — a permit that lands
                // while this thread is parked elsewhere (the read executor's
                // Future.get, a re-entrant publish's write wait) is spent there
                // (JDK 21; measured 2026-09-12), so a wake during a read must be
                // found here, in the queue, or it waits a full tick. A permit that
                // lands between this check and the park is kept (the thread is
                // running), so the park returns at once.
                LockSupport.parkNanos(idleTickNanos);
            }
        }
    }

    /**
     * BUS-ORDER-1: the per-envelope step shared by the LIVE loop and the
     * TRANSITION drain ({@link TransitionCoordinator}). A non-matching envelope
     * only advances the cursor. A matching one goes through the supervisor:
     * SUCCESS advances the cursor and, for a non-atomic subscriber, writes the
     * persisted checkpoint at the delivered position (AMD-45 §2.2 keeps the
     * atomic projection's own sink as its sole writer); PARKED advances the
     * cursor and continues (the DLQ row holds the position — today's semantic);
     * a circuit-breaker trip or an infrastructure failure stops the pass — the
     * supervisor has already SUSPENDED the subscriber.
     *
     * <p>Because the cursor advances past filtered positions and the checkpoint
     * is written only after a matching SUCCESS, the persisted floor can rest
     * below the cursor by the trailing non-matching span; REPLAY re-reads and
     * re-filters that span on restart. The checkpoint is written only when the
     * cursor actually advanced, so it is monotonic by construction.</p>
     *
     * @param runtime         the subscriber's runtime bundle
     * @param envelope        the next envelope beyond the cursor, in position order
     * @param checkpointStore the durable checkpoint store
     * @return the outcome that decides whether the pass continues
     */
    static StepOutcome deliverStep(SubscriberRuntime runtime, EventEnvelope envelope,
                                   CheckpointStore checkpointStore) {
        long position = envelope.globalPosition();
        if (!runtime.info().filter().matches(envelope)) {
            runtime.advanceCursor(position);
            return StepOutcome.FILTERED;
        }
        SubscriberSupervisor.DeliveryResult result =
                runtime.supervisor().deliver(runtime.subscriber(), envelope, runtime);
        return switch (result) {
            case SUCCESS -> {
                boolean advanced = runtime.advanceCursor(position);
                if (advanced && !runtime.info().atomicCheckpoint()) {
                    checkpointStore.writeCheckpoint(runtime.info().subscriberId(), position);
                }
                yield StepOutcome.DELIVERED;
            }
            case PARKED -> {
                runtime.advanceCursor(position);
                yield StepOutcome.PARKED;
            }
            case CIRCUIT_BREAKER_TRIPPED, INFRASTRUCTURE_FAILURE -> StepOutcome.STOP;
        };
    }

    /** The outcome of one {@link #deliverStep}. */
    enum StepOutcome {
        /** The envelope did not match the filter; the cursor moved past it. */
        FILTERED,
        /** Delivered (SUCCESS); the cursor moved and a non-atomic subscriber was checkpointed. */
        DELIVERED,
        /** The supervisor parked the envelope in the DLQ; the cursor moved past it. */
        PARKED,
        /** The supervisor SUSPENDED the subscriber; the pass must end. */
        STOP
    }

    /**
     * Routes one position to an active subscriber by its current mode, with
     * the queue's lock held across the mode read and the routing decision so
     * the TRANSITION → LIVE CAS in {@link TransitionCoordinator} cannot
     * interleave between the observation and the enqueue/offer.
     *
     * @param runtime        the subscriber's runtime bundle
     * @param globalPosition the position to route
     */
    private void routeByMode(SubscriberRuntime runtime, long globalPosition) {
        ReplayWindowQueue queue = runtime.replayWindowQueue();
        queue.lock();
        try {
            SubscriberMode mode = runtime.mode();
            if (mode == SubscriberMode.COLD || mode == SubscriberMode.SUSPENDED) {
                return;
            }
            if (mode == SubscriberMode.REPLAY || mode == SubscriberMode.TRANSITION) {
                // Buffer until coordinator drains. Overflow is recoverable —
                // ReplayDriver observes the latched flag and restarts REPLAY
                // from the persisted checkpoint.
                queue.enqueue(globalPosition);
            } else {
                // LIVE — a wake hint (BUS-ORDER-1); the loop reads the store
                // forward from its cursor and decides what is delivered.
                runtime.wake(globalPosition);
            }
        } finally {
            queue.unlock();
        }
    }

    /**
     * DP-2 (FIX-1b): offers a position whose envelope the publisher's read could
     * not see to every ACTIVE subscriber without filtering — the LIVE loop and
     * the TRANSITION drain filter on their own read-forward, so a matching event
     * is delivered once the store shows it and a non-matching one costs one page
     * read. BUS-ORDER-1: no checkpoint guard on the active path (a wake hint is
     * never conditional on the persisted checkpoint). Passive registrations
     * cannot be filtered without the envelope and are NOT offered; each gets its
     * own {@code NOTIFY_NOT_VISIBLE} anomaly instead — the documented limitation.
     *
     * @param globalPosition the position the publisher's read could not see
     */
    private void offerUnfiltered(long globalPosition) {
        rwLock.readLock().lock();
        try {
            for (PassiveRegistration reg : passiveRegistry.values()) {
                if (checkpointStore.readCheckpoint(reg.info().subscriberId()) < globalPosition) {
                    emitAnomaly(DeliveryAnomaly.Kind.NOTIFY_NOT_VISIBLE, reg.info().subscriberId(),
                            globalPosition, "notifyEvent: passive subscriber not offered — no envelope to filter");
                }
            }
            for (SubscriberRuntime runtime : activeRegistry.values()) {
                routeByMode(runtime, globalPosition);
            }
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Emits one {@link DeliveryAnomaly} through the injected emitter, stamped
     * from the bus's injected clock (FIX-1a). Never throws: a
     * {@link RuntimeException} from the emitter is swallowed, because an
     * instrument must never become a failure channel of its own.
     *
     * @param kind           the drop point
     * @param subscriberId   the subscriber whose delivery dropped, or
     *                       {@link #ANOMALY_ALL_SUBSCRIBERS} before fan-out
     * @param globalPosition the undelivered position
     * @param detail         one line of mechanism
     */
    private void emitAnomaly(DeliveryAnomaly.Kind kind, String subscriberId,
                             long globalPosition, String detail) {
        try {
            anomalyEmitter.accept(new DeliveryAnomaly(
                    subscriberId, globalPosition, kind, detail, clock.instant()));
        } catch (RuntimeException swallowed) {
            // The emitter is an instrument; its failure is not the bus's.
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
                runtime.pendingPositions().size(), // FIX-2b-i: offered to LIVE, not yet consumed
                runtime.supervisor().crashCount(),
                runtime.dlq().oldestParkedAt().orElse(null) // M3.7
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
