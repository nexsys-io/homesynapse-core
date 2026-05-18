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

    private final SubscriberRuntime runtime;
    private final EventStore eventStore;
    private final Clock clock;

    /**
     * Creates a new coordinator bound to the given subscriber runtime.
     *
     * @param runtime    the subscriber's runtime bundle
     * @param eventStore the event store used to load envelopes for queue entries
     * @param clock      the injected clock for DLQ timestamps (NO_DIRECT_TIME_ACCESS)
     * @throws NullPointerException if any argument is {@code null}
     */
    TransitionCoordinator(SubscriberRuntime runtime,
                          EventStore eventStore,
                          Clock clock) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore");
        this.clock = Objects.requireNonNull(clock, "clock");
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
                    return false;
                } catch (Exception e) {
                    runtime.transitionTo(SubscriberMode.SUSPENDED);
                    return false;
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
                    now));
        }

        return true;
    }
}
