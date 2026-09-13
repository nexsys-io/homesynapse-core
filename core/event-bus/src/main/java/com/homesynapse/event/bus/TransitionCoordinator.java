/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event.bus;

import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventStore;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Drains the {@link ReplayWindowQueue}'s wake hints, reads the store forward
 * from the subscriber's cursor to the head, and atomically promotes the
 * subscriber from {@link SubscriberMode#TRANSITION TRANSITION} to
 * {@link SubscriberMode#LIVE LIVE} (AMD-42 §3.4.2 / §3.4.3,
 * PLAN-M3-CONSOLIDATED-02 §6.4; BUS-ORDER-1 / AMD-101 §2 — the flip hands the
 * cursor, not a queue).
 *
 * <p>Runs on the subscriber's dedicated virtual thread after
 * {@link ReplayDriver#run()} returns successfully. The coordinator:</p>
 * <ol>
 *   <li>Drains the {@link ReplayWindowQueue}. Since BUS-ORDER-1 its entries are
 *       wake HINTS: their values only say that work may exist beyond the
 *       cursor; nothing is delivered from them and no gap detection reads them.</li>
 *   <li>Reads the store forward from {@link SubscriberRuntime#cursor()} in pages
 *       of the configured batch through the subscriber's dedicated
 *       {@link SubscriberReadExecutor} (AMD-26/27, INV-SUB-ISO-02) and hands
 *       every envelope, in position order, to
 *       {@link InProcessEventBus#deliverStep} — the same filter / supervisor /
 *       checkpoint discipline as the LIVE loop (FIX-1b: every SUCCESS delivery
 *       of a non-atomic subscriber is checkpointed) — until a short page says
 *       the store shows nothing more.</li>
 *   <li>Once the queue is empty AND remains empty under the queue's own lock
 *       (closing the race with concurrent {@code notifyEvent} enqueues), CASes
 *       mode TRANSITION → LIVE. A hint that lands in between makes the loop
 *       read forward once more.</li>
 *   <li>Fires {@link Subscriber#onCaughtUp()} exactly once (single-shot per
 *       process per subscriber, AMD-42 §3.4.3). Exceptions are caught and parked
 *       in the subscriber's DLQ as a synthetic
 *       {@code CAUGHT_UP_TRANSITION} event-position marker.</li>
 * </ol>
 *
 * <p><strong>Exactly once across the flip (AMD-101 §2).</strong> REPLAY advanced
 * the cursor past every position it paged, so the first read-forward starts
 * where REPLAY stopped; a position appended during the flip is delivered once,
 * by whichever phase reaches it first, and never twice. A page read that throws
 * SUSPENDs the subscriber honestly ({@code drainAndPromote} returns
 * {@code false}) — the pre-FIX-1b arm, unchanged; the FIX-1b per-position
 * retry ({@code TRANSITION_READ_EMPTY} / {@code TRANSITION_READ_EXHAUSTED}) is
 * retired with the single-position read — the kinds stay defined (INV-GA-02),
 * never emitted.</p>
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

    private final SubscriberRuntime runtime;
    private final EventStore eventStore;
    private final CheckpointStore checkpointStore;
    private final Clock clock;
    private final int readBatch;

    /**
     * Creates a new coordinator bound to the given subscriber runtime.
     *
     * @param runtime         the subscriber's runtime bundle
     * @param eventStore      the event store the read-forward pages from
     * @param checkpointStore the durable checkpoint store (FIX-1b) — every
     *                        successful TRANSITION delivery of a non-atomic
     *                        subscriber is checkpointed exactly like a LIVE one
     * @param clock           the injected clock for DLQ timestamps
     *                        (NO_DIRECT_TIME_ACCESS)
     * @param readBatch       events per page read
     *                        ({@code EventBusConfig.liveReadBatch}); {@code >= 1}
     * @throws NullPointerException     if a reference argument is {@code null}
     * @throws IllegalArgumentException if {@code readBatch < 1}
     */
    TransitionCoordinator(SubscriberRuntime runtime,
                          EventStore eventStore,
                          CheckpointStore checkpointStore,
                          Clock clock,
                          int readBatch) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore");
        this.checkpointStore = Objects.requireNonNull(checkpointStore, "checkpointStore");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (readBatch < 1) {
            throw new IllegalArgumentException("readBatch must be >= 1, got: " + readBatch);
        }
        this.readBatch = readBatch;
    }

    /**
     * Drains the replay window queue's hints, reads the store forward from the
     * cursor to the head, and promotes the subscriber to LIVE.
     *
     * @return {@code true} if the subscriber successfully transitioned to LIVE
     *         and {@code onCaughtUp()} was invoked; {@code false} if the
     *         supervisor tripped the circuit breaker, a page read threw (the
     *         subscriber is SUSPENDED), the VT was interrupted, or the CAS to
     *         LIVE failed (mode was already SUSPENDED)
     */
    boolean drainAndPromote() {
        ReplayWindowQueue queue = runtime.replayWindowQueue();

        while (!Thread.currentThread().isInterrupted()) {
            // BUS-ORDER-1: the queued values are wake hints — consume them; the
            // read-forward from the cursor decides what is delivered.
            while (queue.poll() != null) {
                // hints only
            }

            if (!readForwardToHead()) {
                return false; // SUSPENDED (by a delivery or a read), or interrupted
            }

            // Fuse "queue empty?" with the TRANSITION→LIVE CAS under the queue's
            // lock to close the race with notifyEvent's mode-check + enqueue: a
            // hint that landed after the last page read makes the loop read again.
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
                // Else: notifyEvent enqueued under the lock between our last page
                // read and this check — loop back to read forward again.
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
     * BUS-ORDER-1: one read-forward pass — pages the store from the cursor
     * until a short page, handing every envelope to the shared per-envelope
     * step.
     *
     * @return {@code true} when the head was reached; {@code false} when a
     *         delivery stopped the pass (the supervisor SUSPENDED the
     *         subscriber), a page read threw (SUSPENDED here — the REPLAY arm's
     *         shape), or the thread was interrupted (the flag re-asserted)
     */
    private boolean readForwardToHead() {
        while (true) {
            List<EventEnvelope> page;
            try {
                long from = runtime.cursor();
                page = runtime.readExecutor().executeRead(
                        () -> eventStore.readFrom(from, readBatch)).events();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            } catch (Exception e) {
                // A read the store cannot serve: SUSPEND honestly, as REPLAY does.
                runtime.transitionTo(SubscriberMode.SUSPENDED);
                // M3.7 fix round 4: inform the subscriber of its new mode.
                runtime.subscriber().setMode(SubscriberMode.SUSPENDED);
                return false;
            }
            for (EventEnvelope envelope : page) {
                if (InProcessEventBus.deliverStep(runtime, envelope, checkpointStore)
                        == InProcessEventBus.StepOutcome.STOP) {
                    return false;
                }
            }
            if (page.size() < readBatch) {
                return true;
            }
            if (Thread.currentThread().isInterrupted()) {
                return false;
            }
        }
    }
}
