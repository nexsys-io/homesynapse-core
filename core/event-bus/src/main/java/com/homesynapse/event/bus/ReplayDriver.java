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
import java.util.List;
import java.util.Objects;

/**
 * Drives the catch-up phase of a subscriber's REPLAY→LIVE transition
 * (AMD-42 §3.4.2, PLAN-M3-CONSOLIDATED-02 §6.3).
 *
 * <p>Runs on the subscriber's dedicated virtual thread after
 * {@link EventBus#subscribeRuntime(SubscriberInfo, Subscriber)} returns. The
 * driver:</p>
 * <ol>
 *   <li>Reads the subscriber's persisted checkpoint via {@link CheckpointStore}.</li>
 *   <li>CASes the subscriber's mode from {@link SubscriberMode#COLD COLD} to
 *       {@link SubscriberMode#REPLAY REPLAY}.</li>
 *   <li>Pages through the event log via {@link EventStore#readFrom(long, int)}
 *       in bounded windows of {@link #MAX_REPLAY_PAGE} rows (AMD-38).</li>
 *   <li>Delivers matching events through the per-subscriber
 *       {@link SubscriberSupervisor}, tracks
 *       {@link SubscriberRuntime#lastReplayedPosition()} as REPLAY's own
 *       high-water mark, and advances the runtime's read-forward cursor
 *       ({@link SubscriberRuntime#advanceCursor}) past every paged position
 *       so the TRANSITION → LIVE flip hands the cursor (BUS-ORDER-1, AMD-101 §2).</li>
 *   <li>Writes a checkpoint when either {@link #CHECKPOINT_EVENT_THRESHOLD}
 *       events have been processed since the last checkpoint or
 *       {@link #CHECKPOINT_MAX_INTERVAL_SECONDS} seconds have elapsed
 *       (AMD-38).</li>
 *   <li>On {@link ReplayWindowQueue#overflowed() queue overflow}, resets the
 *       reader cursor to the last persisted checkpoint, clears the queue, and
 *       restarts the page-replay loop (PLAN-M3 §6.4).</li>
 *   <li>On tail reach ({@code page.events().isEmpty()}), CASes mode REPLAY →
 *       {@link SubscriberMode#TRANSITION TRANSITION} and returns {@code true}
 *       so the caller can hand off to {@link TransitionCoordinator}.</li>
 * </ol>
 *
 * <p>Reads route through {@link SubscriberReadExecutor#executeRead(java.util.concurrent.Callable)}
 * so the SQLite JNI calls land on the subscriber's dedicated platform thread
 * (AMD-26/27, INV-SUB-ISO-02) — the bus module never touches {@code java.sql}
 * directly.</p>
 *
 * <p><strong>Threading.</strong> A driver instance is bound to a single
 * subscriber and runs on that subscriber's virtual thread only. It is not
 * thread-safe and is not reused across subscribers (INV-SUB-ISO-01).</p>
 *
 * @see TransitionCoordinator
 * @see ReplayWindowQueue
 * @see AMD-42 §3.4.2 "Three-phase REPLAY→LIVE transition"
 */
final class ReplayDriver {

    /** Maximum events per replay page, bounded-window per AMD-38. */
    static final int MAX_REPLAY_PAGE = 500;

    /** Checkpoint cadence: write after this many events processed (AMD-38). */
    static final int CHECKPOINT_EVENT_THRESHOLD = 200;

    /** Checkpoint cadence: write after this many seconds elapsed (AMD-38). */
    static final long CHECKPOINT_MAX_INTERVAL_SECONDS = 2L;

    private final SubscriberRuntime runtime;
    private final EventStore eventStore;
    private final CheckpointStore checkpointStore;
    private final Clock clock;

    /**
     * Creates a new driver bound to the given subscriber runtime.
     *
     * @param runtime         the subscriber's runtime bundle (carries supervisor,
     *                        read executor, mode, replay queue, lastReplayedPosition)
     * @param eventStore      the event store the driver reads pages from
     * @param checkpointStore the durable checkpoint store
     * @param clock           the injected clock for checkpoint cadence (NO_DIRECT_TIME_ACCESS)
     * @throws NullPointerException if any argument is {@code null}
     */
    ReplayDriver(SubscriberRuntime runtime,
                 EventStore eventStore,
                 CheckpointStore checkpointStore,
                 Clock clock) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore");
        this.checkpointStore = Objects.requireNonNull(checkpointStore, "checkpointStore");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Runs the REPLAY phase to completion.
     *
     * @return {@code true} if the driver reached the log tail and successfully
     *         transitioned the subscriber to {@link SubscriberMode#TRANSITION};
     *         {@code false} if the supervisor tripped the circuit breaker
     *         (mode SUSPENDED), the virtual thread was interrupted, or an
     *         infrastructure read failure occurred
     */
    boolean run() {
        String subscriberId = runtime.info().subscriberId();
        SubscriptionFilter filter = runtime.info().filter();

        // (1) Initialize from persisted checkpoint.
        long currentPosition = checkpointStore.readCheckpoint(subscriberId);
        runtime.setLastReplayedPosition(currentPosition);
        // BUS-ORDER-1 (AMD-101 §2): the read-forward cursor starts at the durable floor.
        runtime.advanceCursor(currentPosition);

        // (2) Move out of COLD if we haven't already.
        if (runtime.compareAndTransition(SubscriberMode.COLD, SubscriberMode.REPLAY)) {
            // M3.7 fix round 4: inform the subscriber of its new mode.
            runtime.subscriber().setMode(SubscriberMode.REPLAY);
        }
        if (runtime.mode() != SubscriberMode.REPLAY) {
            // Mode was changed externally (e.g., SUSPENDED) before we started.
            return false;
        }

        long eventsSinceCheckpoint = 0;
        Instant lastCheckpointAt = clock.instant();

        while (!Thread.currentThread().isInterrupted()) {
            // (3) Overflow recovery — restart REPLAY from the persisted checkpoint.
            if (runtime.replayWindowQueue().overflowed()) {
                currentPosition = checkpointStore.readCheckpoint(subscriberId);
                runtime.setLastReplayedPosition(currentPosition);
                runtime.replayWindowQueue().clear();
                eventsSinceCheckpoint = 0;
                lastCheckpointAt = clock.instant();
                continue;
            }

            // (4) Page through the log via the subscriber's dedicated read executor.
            EventPage page;
            try {
                final long pageAfter = currentPosition;
                page = runtime.readExecutor().executeRead(
                        () -> eventStore.readFrom(pageAfter, MAX_REPLAY_PAGE));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            } catch (Exception e) {
                // Infrastructure read failure — suspend the subscriber.
                runtime.transitionTo(SubscriberMode.SUSPENDED);
                // M3.7 fix round 4: inform the subscriber of its new mode.
                runtime.subscriber().setMode(SubscriberMode.SUSPENDED);
                return false;
            }

            List<EventEnvelope> events = page.events();
            if (events.isEmpty()) {
                // (5) Tail reached. Write the final REPLAY checkpoint and hand off.
                // AMD-45 §2.2 / AMD-45-INV-01: skip the bus subscriber-checkpoint
                // write for atomic-checkpoint subscribers on the REPLAY path too —
                // the projection writes the coupled subscriber+view checkpoint via
                // AtomicCheckpointSink, so a bus-side uncoupled write here would
                // advance the subscriber position ahead of the view snapshot and
                // reopen the crash window AMD-45-INV-01 forbids (the invariant is
                // unconditional — it does not carve out REPLAY). Re-replay on
                // restart is idempotent (AMD-45 §2.3), so suppressing this write is
                // safe; the initial-checkpoint read at run() and the overflow read
                // above naturally resume from the projection's coupled checkpoint.
                if (currentPosition > 0L && !runtime.info().atomicCheckpoint()) {
                    checkpointStore.writeCheckpoint(subscriberId, currentPosition);
                }
                boolean swapped = runtime.compareAndTransition(
                        SubscriberMode.REPLAY, SubscriberMode.TRANSITION);
                if (swapped) {
                    // M3.7 fix round 4: inform the subscriber of its new mode.
                    runtime.subscriber().setMode(SubscriberMode.TRANSITION);
                }
                return swapped;
            }

            // (6) Deliver matching events; always advance currentPosition past paged rows.
            for (EventEnvelope envelope : events) {
                if (filter.matches(envelope)) {
                    SubscriberSupervisor.DeliveryResult result =
                            runtime.supervisor().deliver(
                                    runtime.subscriber(), envelope, runtime);
                    if (result == SubscriberSupervisor.DeliveryResult.CIRCUIT_BREAKER_TRIPPED
                            || result == SubscriberSupervisor.DeliveryResult.INFRASTRUCTURE_FAILURE) {
                        return false;
                    }
                    runtime.setLastReplayedPosition(envelope.globalPosition());
                    eventsSinceCheckpoint++;
                }
                currentPosition = envelope.globalPosition();
                // BUS-ORDER-1: the cursor tracks every paged-past position, so the
                // TRANSITION → LIVE flip hands the cursor and the drain's read-forward
                // starts where REPLAY stopped — exactly once across the flip.
                runtime.advanceCursor(currentPosition);
            }

            // (7) Checkpoint cadence per AMD-38: 200 events OR 2 seconds.
            if (shouldCheckpoint(eventsSinceCheckpoint, lastCheckpointAt)) {
                // AMD-45 §2.2 / AMD-45-INV-01: gate the bus subscriber-checkpoint
                // write for atomic-checkpoint subscribers (same reasoning as the
                // tail write above and the LIVE write in InProcessEventBus). The
                // cadence counters still reset so the loop's timing is unchanged;
                // only the uncoupled write is suppressed.
                if (!runtime.info().atomicCheckpoint()) {
                    checkpointStore.writeCheckpoint(subscriberId, currentPosition);
                }
                eventsSinceCheckpoint = 0;
                lastCheckpointAt = clock.instant();
            }
        }

        return false;
    }

    private boolean shouldCheckpoint(long eventsSinceCheckpoint, Instant lastCheckpointAt) {
        if (eventsSinceCheckpoint >= CHECKPOINT_EVENT_THRESHOLD) {
            return true;
        }
        Duration elapsed = Duration.between(lastCheckpointAt, clock.instant());
        return elapsed.getSeconds() >= CHECKPOINT_MAX_INTERVAL_SECONDS;
    }
}
