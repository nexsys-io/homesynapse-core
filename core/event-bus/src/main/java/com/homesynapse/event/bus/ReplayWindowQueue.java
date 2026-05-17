/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event.bus;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Bounded in-memory queue for events arriving during REPLAY (AMD-42 §3.4.2).
 *
 * <p>When a subscriber is in REPLAY mode, new live events that arrive via
 * {@code notifyEvent} are buffered in this queue. During the TRANSITION phase
 * (M3.2), these queued positions are drained before the subscriber enters LIVE
 * mode.</p>
 *
 * <p>M3.1 creates this type with a {@link #size()} accessor. M3.2 implements
 * the drain logic and overflow detection (CRITICAL alert at 10,000 entries).</p>
 *
 * <p>This class is NOT thread-safe — access is serialized by the subscriber's
 * mode FSM and dedicated virtual thread.</p>
 */
final class ReplayWindowQueue {

    private final Deque<Long> queue = new ArrayDeque<>();

    /**
     * Creates a new empty replay window queue.
     */
    ReplayWindowQueue() {
        // Package-private constructor.
    }

    /**
     * Enqueues a global position for later drain during TRANSITION.
     *
     * @param globalPosition the event position to buffer
     */
    void enqueue(long globalPosition) {
        queue.addLast(globalPosition);
    }

    /**
     * Returns the number of buffered positions in the queue.
     *
     * @return the current queue size
     */
    int size() {
        return queue.size();
    }

    /**
     * Clears all buffered positions.
     */
    void clear() {
        queue.clear();
    }
}
