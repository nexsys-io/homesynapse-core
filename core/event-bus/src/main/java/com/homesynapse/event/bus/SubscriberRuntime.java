/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event.bus;

import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * Internal bundle holding per-subscriber runtime resources (AMD-42 §3.4.4).
 *
 * <p>Each subscriber registered via {@link EventBus#subscribeRuntime(SubscriberInfo, Subscriber)}
 * gets its own {@code SubscriberRuntime} containing:</p>
 * <ul>
 *   <li>The subscriber callback</li>
 *   <li>The virtual thread handle</li>
 *   <li>The dedicated read executor</li>
 *   <li>The supervisor (exception handling, backoff, circuit breaker)</li>
 *   <li>The DLQ ring</li>
 *   <li>The atomic mode reference</li>
 *   <li>The replay window queue</li>
 *   <li>The read-forward cursor and the wake-hint queue (BUS-ORDER-1, AMD-101 §2)</li>
 * </ul>
 *
 * <p>This class is package-private — only {@link InProcessEventBus} interacts with it.</p>
 */
final class SubscriberRuntime {

    private final SubscriberInfo info;
    private final Subscriber subscriber;
    private final AtomicReference<SubscriberMode> mode;
    private final SubscriberReadExecutor readExecutor;
    private final SubscriberSupervisor supervisor;
    private final SubscriberDlq dlq;
    private final ReplayWindowQueue replayWindowQueue;
    private final LinkedBlockingQueue<Long> pendingPositions = new LinkedBlockingQueue<>();
    private final AtomicLong lastReplayedPosition = new AtomicLong(0L);
    /**
     * BUS-ORDER-1 (AMD-101 §2): the read-forward cursor — the highest global
     * position delivered or filtered past in this activation. Initialised from
     * the persisted checkpoint when REPLAY starts, advanced by REPLAY's paging,
     * the TRANSITION drain and the LIVE loop; only ever moves forward (a
     * CAS-max). The persisted checkpoint follows it and is therefore monotonic.
     */
    private final AtomicLong cursor = new AtomicLong(0L);
    /**
     * Per-subscriber derived-write rate limit (AMD-43 §3.6.4).
     * Nullable — only derivation-producing subscribers (e.g. State Projection,
     * wired in M3.5a) carry one. M3.3 sets this to {@code null} for all
     * subscribers; the field exists to keep the runtime bundle stable across
     * milestones.
     */
    private final DerivedWriteRateLimit rateLimit;
    private volatile Thread virtualThread;

    /**
     * Creates a new subscriber runtime bundle without a rate limit (the common
     * M3.3 case — only derivation-producing subscribers carry one, and those
     * are wired in M3.5a).
     *
     * @param info              the subscriber registration metadata
     * @param subscriber        the subscriber callback
     * @param readExecutor      the dedicated read executor
     * @param supervisor        the exception-handling supervisor
     * @param dlq               the in-memory DLQ ring
     * @param replayWindowQueue the replay window buffer
     */
    SubscriberRuntime(SubscriberInfo info,
                      Subscriber subscriber,
                      SubscriberReadExecutor readExecutor,
                      SubscriberSupervisor supervisor,
                      SubscriberDlq dlq,
                      ReplayWindowQueue replayWindowQueue) {
        this(info, subscriber, readExecutor, supervisor, dlq, replayWindowQueue, null);
    }

    /**
     * Creates a new subscriber runtime bundle with an optional rate limit.
     *
     * @param info              the subscriber registration metadata
     * @param subscriber        the subscriber callback
     * @param readExecutor      the dedicated read executor
     * @param supervisor        the exception-handling supervisor
     * @param dlq               the in-memory DLQ ring
     * @param replayWindowQueue the replay window buffer
     * @param rateLimit         per-subscriber derived-write rate limit, or
     *                          {@code null} for non-derivation-producing subscribers
     */
    SubscriberRuntime(SubscriberInfo info,
                      Subscriber subscriber,
                      SubscriberReadExecutor readExecutor,
                      SubscriberSupervisor supervisor,
                      SubscriberDlq dlq,
                      ReplayWindowQueue replayWindowQueue,
                      DerivedWriteRateLimit rateLimit) {
        this.info = Objects.requireNonNull(info, "info");
        this.subscriber = Objects.requireNonNull(subscriber, "subscriber");
        this.readExecutor = Objects.requireNonNull(readExecutor, "readExecutor");
        this.supervisor = Objects.requireNonNull(supervisor, "supervisor");
        this.dlq = Objects.requireNonNull(dlq, "dlq");
        this.replayWindowQueue = Objects.requireNonNull(replayWindowQueue, "replayWindowQueue");
        this.rateLimit = rateLimit; // nullable
        this.mode = new AtomicReference<>(SubscriberMode.COLD);
    }

    /**
     * Returns the per-subscriber derived-write rate limit, or {@code null}
     * if this subscriber does not produce derived writes.
     *
     * @return the rate limit, or {@code null}
     */
    DerivedWriteRateLimit rateLimit() {
        return rateLimit;
    }

    /**
     * Returns the subscriber registration metadata.
     *
     * @return the subscriber info
     */
    SubscriberInfo info() {
        return info;
    }

    /**
     * Returns the subscriber callback.
     *
     * @return the subscriber
     */
    Subscriber subscriber() {
        return subscriber;
    }

    /**
     * Returns the current subscriber mode atomically.
     *
     * @return the current mode
     */
    SubscriberMode mode() {
        return mode.get();
    }

    /**
     * Atomically transitions the subscriber to a new mode via CAS.
     *
     * @param newMode the target mode
     */
    void transitionTo(SubscriberMode newMode) {
        mode.set(newMode);
    }

    /**
     * Atomically transitions via CAS only if the current mode matches expected.
     *
     * @param expected the expected current mode
     * @param newMode  the target mode
     * @return {@code true} if the transition succeeded
     */
    boolean compareAndTransition(SubscriberMode expected, SubscriberMode newMode) {
        return mode.compareAndSet(expected, newMode);
    }

    /**
     * Returns the dedicated read executor for this subscriber.
     *
     * @return the read executor
     */
    SubscriberReadExecutor readExecutor() {
        return readExecutor;
    }

    /**
     * Returns the supervisor managing exception handling for this subscriber.
     *
     * @return the supervisor
     */
    SubscriberSupervisor supervisor() {
        return supervisor;
    }

    /**
     * Returns the in-memory DLQ ring for this subscriber.
     *
     * @return the DLQ
     */
    SubscriberDlq dlq() {
        return dlq;
    }

    /**
     * Returns the replay window queue for this subscriber.
     *
     * @return the replay window queue
     */
    ReplayWindowQueue replayWindowQueue() {
        return replayWindowQueue;
    }

    /**
     * Returns the wake-hint queue for this subscriber's LIVE loop.
     *
     * <p>BUS-ORDER-1 (AMD-101 §2): the queue is the wake signal's carrier, not
     * the unit of delivery. {@code notifyEvent} offers the notified position as
     * a HINT through {@link #wake(long)}; the LIVE loop drains the queue
     * ({@link #drainHints()}) without acting on the values and delivers by
     * reading the store forward from {@link #cursor()}. Its size is
     * {@code SubscriberSnapshot.pendingDepth} — un-consumed hints.</p>
     *
     * @return the wake-hint queue
     */
    LinkedBlockingQueue<Long> pendingPositions() {
        return pendingPositions;
    }

    /**
     * Returns the read-forward cursor: the highest global position delivered or
     * filtered past in this activation (BUS-ORDER-1, AMD-101 §2).
     *
     * @return the cursor; {@code 0} until the activation has read its checkpoint
     */
    long cursor() {
        return cursor.get();
    }

    /**
     * Advances the cursor to {@code position} if it lies beyond the current
     * value — a CAS-max: the cursor never moves backwards (LTD-11: a CAS,
     * never a monitor).
     *
     * @param position the position just delivered or filtered past
     * @return {@code true} when the cursor advanced; {@code false} when it was
     *         already at or past {@code position}
     */
    boolean advanceCursor(long position) {
        return cursor.getAndAccumulate(position, Math::max) < position;
    }

    /**
     * Wakes the LIVE loop: offers {@code position} as a hint into the wake-hint
     * queue and unparks the subscriber's virtual thread. Never skipped and never
     * conditional on any checkpoint (AMD-101 §2 — the notification carries no
     * delivery decision); a wake with nothing new beyond the cursor costs one
     * empty page read.
     *
     * @param position the notified position, kept only as a diagnostic hint
     */
    void wake(long position) {
        pendingPositions.offer(position);
        Thread vt = virtualThread;
        if (vt != null) {
            LockSupport.unpark(vt);
        }
    }

    /**
     * Consumes every queued wake hint without acting on the values; the LIVE
     * loop calls it before each read-forward pass.
     */
    void drainHints() {
        while (pendingPositions.poll() != null) {
            // hints only — the read-forward from the cursor decides what is delivered
        }
    }

    /**
     * Returns the highest global position successfully delivered (or attempted via the
     * supervisor) to the subscriber during REPLAY and TRANSITION.
     *
     * <p>Used by {@link TransitionCoordinator} for gap detection when draining the
     * {@link ReplayWindowQueue}: queue entries with {@code globalPosition <=
     * lastReplayedPosition} have already been delivered via the REPLAY paging loop
     * and are skipped to satisfy INV-BUS-01 (no duplicate delivery at the
     * REPLAY→LIVE boundary).</p>
     *
     * @return the highest delivered global position, or 0 if none yet
     */
    long lastReplayedPosition() {
        return lastReplayedPosition.get();
    }

    /**
     * Sets the highest delivered global position. Called by {@link ReplayDriver}
     * after each successful supervisor delivery during REPLAY and by
     * {@link TransitionCoordinator} during drain.
     *
     * @param position the new high-water mark
     */
    void setLastReplayedPosition(long position) {
        lastReplayedPosition.set(position);
    }

    /**
     * Sets the subscriber's virtual thread handle.
     *
     * @param thread the virtual thread
     */
    void setVirtualThread(Thread thread) {
        this.virtualThread = thread;
    }

    /**
     * Returns the subscriber's virtual thread handle.
     *
     * @return the virtual thread, or {@code null} if not yet started
     */
    Thread virtualThread() {
        return virtualThread;
    }

    /**
     * Closes the runtime, releasing all resources.
     *
     * <p>Interrupts the virtual thread and closes the read executor.</p>
     */
    void close() {
        Thread vt = virtualThread;
        if (vt != null && vt.isAlive()) {
            vt.interrupt();
        }
        readExecutor.close();
        dlq.clear();
        replayWindowQueue.clear();
        if (rateLimit != null) {
            rateLimit.close();
        }
    }
}
