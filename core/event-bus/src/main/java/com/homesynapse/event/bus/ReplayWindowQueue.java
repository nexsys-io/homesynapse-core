/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event.bus;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Bounded in-memory queue for events arriving during REPLAY (AMD-42 §3.4.2,
 * PLAN-M3-CONSOLIDATED-02 §6.4).
 *
 * <p>While a subscriber is in {@link SubscriberMode#REPLAY REPLAY} (or
 * {@link SubscriberMode#TRANSITION TRANSITION}), the bus routes incoming
 * {@code notifyEvent} positions into this per-subscriber queue rather than into
 * the LIVE pull path. The {@link TransitionCoordinator} drains the queue at
 * TRANSITION time, applying gap detection against
 * {@link SubscriberRuntime#lastReplayedPosition()} so events already delivered
 * during REPLAY are not re-delivered.</p>
 *
 * <p>The queue is bounded at {@link #MAX_CAPACITY} entries. An enqueue that
 * would exceed the bound returns {@code false} and latches an overflow flag
 * (see {@link #overflowed()}). The {@link ReplayDriver} observes the flag and
 * restarts REPLAY from the subscriber's most recently written checkpoint
 * position — overflow is recoverable, not a data-loss event (INV-ES-05).</p>
 *
 * <p><strong>Thread safety.</strong> All operations are guarded by a
 * {@link ReentrantLock} per LTD-11 (no {@code synchronized} — virtual threads
 * pin on monitor entry). {@link #lock()} and {@link #unlock()} expose the
 * internal lock so callers can perform atomic compound operations — the
 * coordinator uses this to fuse "queue empty?" with the TRANSITION→LIVE CAS,
 * and the bus uses it to fuse "what mode is this subscriber in?" with the
 * routing decision in {@code notifyEvent}.</p>
 *
 * <p>Lifetime is REPLAY entry → drain complete (INV-SUB-ISO-05). The queue is
 * cleared on {@link #clear()} and on subscriber close.</p>
 */
final class ReplayWindowQueue {

    /** Maximum capacity per AMD-42 §3.4.2 — overflow triggers REPLAY restart. */
    static final int MAX_CAPACITY = 10_000;

    private final ReentrantLock mutex = new ReentrantLock();
    private final Deque<Long> queue = new ArrayDeque<>();
    private final AtomicBoolean overflowed = new AtomicBoolean(false);

    /**
     * Creates a new empty replay window queue.
     */
    ReplayWindowQueue() {
        // Package-private constructor.
    }

    /**
     * Attempts to enqueue a global position for later drain during TRANSITION.
     *
     * <p>If the queue is at {@link #MAX_CAPACITY}, the entry is rejected, the
     * overflow flag is latched, and {@code false} is returned. Overflow is
     * recoverable: the {@link ReplayDriver} observes the flag and restarts
     * REPLAY from the most recently checkpointed position.</p>
     *
     * @param globalPosition the event position to buffer
     * @return {@code true} if the entry was accepted; {@code false} on overflow
     */
    boolean enqueue(long globalPosition) {
        mutex.lock();
        try {
            if (queue.size() >= MAX_CAPACITY) {
                overflowed.set(true);
                return false;
            }
            queue.addLast(globalPosition);
            return true;
        } finally {
            mutex.unlock();
        }
    }

    /**
     * Removes and returns the head position, or {@code null} if empty.
     *
     * @return the next buffered position, or {@code null} when empty
     */
    Long poll() {
        mutex.lock();
        try {
            return queue.pollFirst();
        } finally {
            mutex.unlock();
        }
    }

    /**
     * Returns the number of buffered positions in the queue.
     *
     * @return the current queue size
     */
    int size() {
        mutex.lock();
        try {
            return queue.size();
        } finally {
            mutex.unlock();
        }
    }

    /**
     * Returns {@code true} if the queue contains no buffered positions.
     *
     * @return {@code true} when the queue is empty
     */
    boolean isEmpty() {
        mutex.lock();
        try {
            return queue.isEmpty();
        } finally {
            mutex.unlock();
        }
    }

    /**
     * Returns {@code true} if any enqueue attempt has exceeded
     * {@link #MAX_CAPACITY} since the last {@link #clear()}.
     *
     * @return the latched overflow indicator
     */
    boolean overflowed() {
        return overflowed.get();
    }

    /**
     * Clears all buffered positions and resets the overflow flag.
     */
    void clear() {
        mutex.lock();
        try {
            queue.clear();
            overflowed.set(false);
        } finally {
            mutex.unlock();
        }
    }

    /**
     * Acquires the queue's internal lock for compound atomic operations.
     *
     * <p>Used by the bus's {@code notifyEvent} (fuses mode-read with enqueue)
     * and by {@link TransitionCoordinator} (fuses empty-check with the
     * TRANSITION→LIVE CAS). The lock is a {@link ReentrantLock} so nested
     * calls from the same thread are safe.</p>
     */
    void lock() {
        mutex.lock();
    }

    /**
     * Releases the queue's internal lock acquired via {@link #lock()}.
     */
    void unlock() {
        mutex.unlock();
    }
}
