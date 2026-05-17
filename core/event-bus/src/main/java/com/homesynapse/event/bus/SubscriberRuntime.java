/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event.bus;

import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

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
    private volatile Thread virtualThread;

    /**
     * Creates a new subscriber runtime bundle.
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
        this.info = Objects.requireNonNull(info, "info");
        this.subscriber = Objects.requireNonNull(subscriber, "subscriber");
        this.readExecutor = Objects.requireNonNull(readExecutor, "readExecutor");
        this.supervisor = Objects.requireNonNull(supervisor, "supervisor");
        this.dlq = Objects.requireNonNull(dlq, "dlq");
        this.replayWindowQueue = Objects.requireNonNull(replayWindowQueue, "replayWindowQueue");
        this.mode = new AtomicReference<>(SubscriberMode.COLD);
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
     * Returns the pending positions queue for this subscriber's VT to process.
     *
     * <p>Positions are enqueued by {@code notifyEvent} and dequeued by the
     * subscriber's virtual thread loop.</p>
     *
     * @return the pending positions queue
     */
    LinkedBlockingQueue<Long> pendingPositions() {
        return pendingPositions;
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
    }
}
