/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event.bus;

import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventPage;
import com.homesynapse.event.EventStore;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

/**
 * Drains the {@link ReplayWindowQueue} and atomically promotes a subscriber
 * from {@link SubscriberMode#TRANSITION TRANSITION} to {@link SubscriberMode#LIVE LIVE}
 * (AMD-42 §3.4.2 / §3.4.3, PLAN-M3-CONSOLIDATED-02 §6.4).
 *
 * <p>Runs on the subscriber's dedicated virtual thread after
 * {@link ReplayDriver#run()} returns successfully. The coordinator:</p>
 * <ol>
 *   <li>Drains the {@link ReplayWindowQueue} in {@code globalPosition} order.</li>
 *   <li>For each entry, applies gap detection against
 *       {@link SubscriberRuntime#lastReplayedPosition()} — entries at or below
 *       that high-water mark were already delivered during REPLAY and are
 *       skipped (INV-BUS-01 — at-least-once delivery with no spurious duplicates
 *       at the boundary).</li>
 *   <li>Loads each non-skipped event from {@link EventStore} through the
 *       subscriber's dedicated {@link SubscriberReadExecutor} (AMD-26/27,
 *       INV-SUB-ISO-02).</li>
 *   <li>Delivers each event via {@link SubscriberSupervisor#deliver}, updating
 *       {@code lastReplayedPosition} on attempted delivery.</li>
 *   <li>Once the queue is empty AND remains empty under the queue's own lock
 *       (closing the race with concurrent {@code notifyEvent} enqueues), CASes
 *       mode {@link SubscriberMode#TRANSITION TRANSITION} → {@link SubscriberMode#LIVE LIVE}.</li>
 *   <li>Fires {@link Subscriber#onCaughtUp()} exactly once (single-shot per
 *       process per subscriber, AMD-42 §3.4.3). Exceptions are caught and parked
 *       in the subscriber's DLQ as a synthetic
 *       {@code CAUGHT_UP_TRANSITION} event-position marker.</li>
 * </ol>
 *
 * <p><strong>Threading.</strong> A coordinator instance is bound to a single
 * subscriber and runs on that subscriber's virtual thread only. It is not
 * thread-safe and is not reused.</p>
 *
 * @see ReplayDriver
 * @see ReplayWindowQueue
 */
final class TransitionCoordinator {

    /** Synthetic event-position marker for onCaughtUp DLQ entries (AMD-42 §3.4.3). */
    static final long CAUGHT_UP_TRANSITION_MARKER = -1L;

    /**
     * DP-2 (FIX-1b): single-position read attempts on one queued position
     * before the subscriber SUSPENDs honestly; the same shape as the LIVE
     * loop's {@code InProcessEventBus.LIVE_READ_ATTEMPTS}.
     */
    static final int DRAIN_READ_ATTEMPTS = 5;

    /** DP-2 (FIX-1b): the first inter-attempt park (1 → 2 → 4 → 8 ms); a park is not a clock read. */
    static final long DRAIN_READ_BACKOFF_FIRST_NANOS = 1_000_000L;

    private final SubscriberRuntime runtime;
    private final EventStore eventStore;
    private final CheckpointStore checkpointStore;
    private final Clock clock;
    private final Consumer<DeliveryAnomaly> anomalyEmitter;

    /**
     * Creates a new coordinator bound to the given subscriber runtime.
     *
     * @param runtime         the subscriber's runtime bundle
     * @param eventStore      the event store used to load envelopes for queue entries
     * @param checkpointStore the durable checkpoint store (FIX-1b) — every
     *                        successful TRANSITION delivery of a non-atomic
     *                        subscriber is checkpointed exactly like a LIVE one
     * @param clock           the injected clock for DLQ and anomaly timestamps
     *                        (NO_DIRECT_TIME_ACCESS)
     * @param anomalyEmitter  the bus's delivery-anomaly emitter (FIX-1a) — a
     *                        queued position whose read returns an empty page is
     *                        reported through it, retried, and on exhaustion
     *                        ends in an honest SUSPEND (FIX-1b); never {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    TransitionCoordinator(SubscriberRuntime runtime,
                          EventStore eventStore,
                          CheckpointStore checkpointStore,
                          Clock clock,
                          Consumer<DeliveryAnomaly> anomalyEmitter) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore");
        this.checkpointStore = Objects.requireNonNull(checkpointStore, "checkpointStore");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.anomalyEmitter = Objects.requireNonNull(anomalyEmitter, "anomalyEmitter");
    }

    /**
     * Drains the replay window queue and promotes the subscriber to LIVE.
     *
     * @return {@code true} if the subscriber successfully transitioned to LIVE
     *         and {@code onCaughtUp()} was invoked; {@code false} if the
     *         supervisor tripped the circuit breaker, the VT was interrupted,
     *         or the CAS to LIVE failed (mode was already SUSPENDED)
     */
    boolean drainAndPromote() {
        SubscriptionFilter filter = runtime.info().filter();
        ReplayWindowQueue queue = runtime.replayWindowQueue();

        while (!Thread.currentThread().isInterrupted()) {
            // Drain any entries currently visible in the queue.
            Long position;
            while ((position = queue.poll()) != null) {
                if (position <= runtime.lastReplayedPosition()) {
                    continue; // gap detection — already delivered during REPLAY
                }

                // FIX-1b (DP-2): a queued position the store cannot show is
                // retried, then an honest SUSPEND — never a silent skip.
                EventEnvelope envelope = readQueuedPosition(position);
                if (envelope == null) {
                    return false; // SUSPENDED (exhausted, or the read threw) or interrupted
                }

                if (!filter.matches(envelope)) {
                    runtime.setLastReplayedPosition(envelope.globalPosition());
                    continue;
                }

                SubscriberSupervisor.DeliveryResult result =
                        runtime.supervisor().deliver(
                                runtime.subscriber(), envelope, runtime);
                if (result == SubscriberSupervisor.DeliveryResult.CIRCUIT_BREAKER_TRIPPED
                        || result == SubscriberSupervisor.DeliveryResult.INFRASTRUCTURE_FAILURE) {
                    return false;
                }
                runtime.setLastReplayedPosition(envelope.globalPosition());
                if (result == SubscriberSupervisor.DeliveryResult.SUCCESS
                        && !runtime.info().atomicCheckpoint()) {
                    // FIX-1b: a TRANSITION delivery checkpoints exactly like a LIVE
                    // one (the AMD-45 §2.2 gate kept). Before this write, a publish
                    // burst that ended inside TRANSITION was fully delivered yet left
                    // the persisted checkpoint at the REPLAY tail — the
                    // ReplayTransitionIT phase-1 stall (measured 2026-09-05).
                    checkpointStore.writeCheckpoint(
                            runtime.info().subscriberId(), envelope.globalPosition());
                }
            }

            // Fuse "queue empty?" with the TRANSITION→LIVE CAS under the queue's
            // lock to close the race with notifyEvent's mode-check + enqueue.
            queue.lock();
            try {
                if (queue.isEmpty()) {
                    if (!runtime.compareAndTransition(
                            SubscriberMode.TRANSITION, SubscriberMode.LIVE)) {
                        return false; // mode raced out from under us (e.g., SUSPENDED)
                    }
                    // M3.7 fix round 4: inform the subscriber of its new mode.
                    // Called under queue.lock(); StateProjection.setMode is a
                    // non-blocking AtomicReference.set per the contract.
                    runtime.subscriber().setMode(SubscriberMode.LIVE);
                    break; // committed to LIVE, exit drain loop
                }
                // Else: notifyEvent enqueued under the lock between our last poll
                // and this check — loop back to drain the new entry.
            } finally {
                queue.unlock();
            }
        }

        // Mode is now LIVE. Fire onCaughtUp exactly once per AMD-42 §3.4.3.
        // Exceptions become a synthetic DLQ entry (CAUGHT_UP_TRANSITION marker)
        // rather than tripping the supervisor's normal RuntimeException path.
        try {
            runtime.subscriber().onCaughtUp();
        } catch (Throwable t) {
            Instant now = clock.instant();
            String message = t.getMessage() != null
                    ? t.getMessage()
                    : t.getClass().getSimpleName();
            runtime.dlq().park(new SubscriberDlq.DlqEntry(
                    CAUGHT_UP_TRANSITION_MARKER,
                    t.getClass().getName(),
                    "onCaughtUp: " + message,
                    1,
                    now,
                    now,
                    now)); // M3.7 — parkedAt stamped by the coordinator's clock
        }

        return true;
    }

    /**
     * DP-2 (FIX-1b): reads one queued position through the subscriber's
     * dedicated read executor with a bounded retry. An empty page emits
     * {@code TRANSITION_READ_EMPTY} and is retried after a doubling park; on
     * exhaustion the coordinator emits {@code TRANSITION_READ_EXHAUSTED} and
     * SUSPENDs the subscriber. A read exception SUSPENDs immediately (the
     * pre-FIX-1b honest arm, unchanged); an interrupt re-asserts the flag.
     *
     * @param pos the queued position
     * @return the envelope, or {@code null} when {@code drainAndPromote} must
     *         return {@code false}
     */
    private EventEnvelope readQueuedPosition(long pos) {
        long backoff = DRAIN_READ_BACKOFF_FIRST_NANOS;
        for (int attempt = 1; attempt <= DRAIN_READ_ATTEMPTS; attempt++) {
            try {
                EventPage page = runtime.readExecutor().executeRead(
                        () -> eventStore.readFrom(pos - 1, 1));
                if (!page.events().isEmpty()) {
                    return page.events().get(0);
                }
                emitAnomaly(DeliveryAnomaly.Kind.TRANSITION_READ_EMPTY, pos,
                        "drainAndPromote: no envelope at position");
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            } catch (Exception e) {
                runtime.transitionTo(SubscriberMode.SUSPENDED);
                // M3.7 fix round 4: inform the subscriber of its new mode.
                runtime.subscriber().setMode(SubscriberMode.SUSPENDED);
                return null;
            }
            if (attempt < DRAIN_READ_ATTEMPTS) {
                LockSupport.parkNanos(backoff);
                backoff <<= 1;
            }
        }
        emitAnomaly(DeliveryAnomaly.Kind.TRANSITION_READ_EXHAUSTED, pos,
                "drainAndPromote: position unreadable after " + DRAIN_READ_ATTEMPTS
                        + " attempts; subscriber SUSPENDED");
        runtime.transitionTo(SubscriberMode.SUSPENDED);
        // M3.7 fix round 4: inform the subscriber of its new mode.
        runtime.subscriber().setMode(SubscriberMode.SUSPENDED);
        return null;
    }

    /**
     * Emits a {@link DeliveryAnomaly} of the given kind for this subscriber
     * (FIX-1a/1b). Never throws — a {@link RuntimeException} from the emitter
     * is swallowed; an instrument must never become a failure channel.
     *
     * @param kind           the drop point
     * @param globalPosition the queued position the store could not show
     * @param detail         one line of mechanism
     */
    private void emitAnomaly(DeliveryAnomaly.Kind kind, long globalPosition, String detail) {
        try {
            anomalyEmitter.accept(new DeliveryAnomaly(
                    runtime.info().subscriberId(), globalPosition, kind, detail, clock.instant()));
        } catch (RuntimeException swallowed) {
            // The emitter is an instrument; its failure is not the coordinator's.
        }
    }
}
