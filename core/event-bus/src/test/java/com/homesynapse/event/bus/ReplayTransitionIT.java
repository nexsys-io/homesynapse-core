/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event.bus;

import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.SequenceConflictException;
import com.homesynapse.event.bus.test.InMemoryCheckpointStore;
import com.homesynapse.event.bus.test.RecordingReadConnectionFactory;
import com.homesynapse.event.test.InMemoryEventStore;
import com.homesynapse.event.test.TestEventFactory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the full M3.2 three-phase REPLAY→TRANSITION→LIVE
 * algorithm under continuous publish across a simulated process restart.
 *
 * <p>Implements the scenario specified in
 * PLAN-M3-CONSOLIDATED-02 §6.6 / AMD-42 §3.4.2:</p>
 * <ol>
 *   <li>Start bus 1, register subscriber S1, publish 1,000 events, wait for the
 *       persisted checkpoint to reach {@code globalPosition = 1000}.</li>
 *   <li>Simulate restart: tear down bus 1 (the {@link InMemoryEventStore} and
 *       {@link InMemoryCheckpointStore} are retained — they model the durable
 *       SQLite stores) and stand up a fresh bus 2.</li>
 *   <li>Concurrently: re-register S1 with the same {@code subscriberId} (so its
 *       persisted checkpoint of 1000 is loaded) AND start a publisher emitting
 *       500 more events at roughly 100/sec.</li>
 *   <li>The new S1 progresses COLD → REPLAY (reads checkpoint 1000) → processes
 *       events that arrive during REPLAY → TRANSITION (drains the replay window
 *       queue with gap detection) → LIVE.</li>
 *   <li>Assert: S1's recorded list, deduped, contains exactly
 *       {@code 1..1500} in {@code globalPosition} order — no gaps, no missed
 *       events.</li>
 *   <li>Assert: {@code onCaughtUp()} fired exactly once during phase 2
 *       (single-shot per process per subscriber, AMD-42 §3.4.3).</li>
 * </ol>
 *
 * <p>Time is supplied via an injected {@link Clock} per LTD-09 /
 * {@code NO_DIRECT_TIME_ACCESS}. Waiting in this test uses fixed-interval
 * {@link Thread#sleep(long)} polling rather than any direct clock read.</p>
 */
@DisplayName("M3.2 — REPLAY→TRANSITION→LIVE integration test")
class ReplayTransitionIT {

    private static final Instant EPOCH = Instant.parse("2026-05-01T00:00:00Z");

    /** Creates a new test instance. */
    ReplayTransitionIT() {
        // Explicit constructor per -Xlint:all -Werror requirement.
    }

    @Test
    @DisplayName("subscriber resumes from persisted checkpoint and catches up "
            + "to LIVE without gaps under continuous publish")
    void replayTransitionLiveEndToEnd() throws Exception {
        Clock clock = Clock.fixed(EPOCH, ZoneOffset.UTC);

        // Stores are retained across the simulated restart — they model the
        // durable SQLite event store + checkpoint store.
        InMemoryEventStore eventStore = new InMemoryEventStore(clock);
        InMemoryCheckpointStore checkpointStore = new InMemoryCheckpointStore();
        RecordingReadConnectionFactory connectionFactory =
                new RecordingReadConnectionFactory();

        String subscriberId = "replay-it-sub";
        List<Long> recordedPositions = new CopyOnWriteArrayList<>();
        AtomicInteger caughtUpCount = new AtomicInteger();
        Subscriber recorder = new Subscriber() {
            @Override
            public void onEvent(EventEnvelope event) {
                recordedPositions.add(event.globalPosition());
            }

            @Override
            public void onCaughtUp() {
                caughtUpCount.incrementAndGet();
            }
        };

        // ── Phase 1 — initial run: 1,000 events end-to-end ───────────
        InProcessEventBus bus1 = new InProcessEventBus(
                eventStore, checkpointStore, clock, connectionFactory);
        bus1.subscribeRuntime(
                new SubscriberInfo(subscriberId, SubscriptionFilter.all(), false),
                recorder);

        // CopyOnWriteArrayList for cross-thread visibility without locks (LTD-11).
        List<Long> emittedPositions = new CopyOnWriteArrayList<>();
        AtomicLong highestEmitted = new AtomicLong(0L);
        for (int i = 0; i < 1000; i++) {
            EventEnvelope env = eventStore.publishRoot(TestEventFactory.draft());
            bus1.notifyEvent(env.globalPosition());
            emittedPositions.add(env.globalPosition());
            highestEmitted.set(env.globalPosition());
        }

        awaitCheckpoint(checkpointStore, subscriberId, 1000L, 15_000L);

        int phase1CaughtUp = caughtUpCount.get();
        int phase1Delivered = recordedPositions.size();
        assertThat(phase1CaughtUp)
                .as("onCaughtUp fires exactly once during phase 1")
                .isEqualTo(1);
        assertThat(phase1Delivered)
                .as("All 1000 phase-1 events delivered")
                .isEqualTo(1000);

        // ── Simulate restart ─────────────────────────────────────────
        bus1.reset();
        // Brief settle for any in-flight delivery on bus1's VT.
        Thread.sleep(100L);

        // ── Phase 2 — restart with concurrent publisher ──────────────
        InProcessEventBus bus2 = new InProcessEventBus(
                eventStore, checkpointStore, clock, connectionFactory);

        // Concurrent publisher thread emitting 500 events at ~100/sec.
        // emittedPositions is a CopyOnWriteArrayList — thread-safe without
        // synchronized (LTD-11). highestEmitted carries the tail position to
        // the main thread for the post-join checkpoint wait.
        Thread publisher = new Thread(() -> {
            for (int i = 0; i < 500; i++) {
                try {
                    EventEnvelope env = eventStore.publishRoot(
                            TestEventFactory.draft());
                    bus2.notifyEvent(env.globalPosition());
                    emittedPositions.add(env.globalPosition());
                    highestEmitted.set(env.globalPosition());
                    Thread.sleep(10L); // ~100 events/sec
                } catch (SequenceConflictException | InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            }
        }, "replay-it-publisher");

        // Register the subscriber and start the publisher concurrently — the
        // race between subscriber registration, the driver paging the existing
        // log, and live publishes is exactly what M3.2 must handle correctly.
        bus2.subscribeRuntime(
                new SubscriberInfo(subscriberId, SubscriptionFilter.all(), false),
                recorder);
        publisher.start();

        publisher.join(60_000L);
        assertThat(publisher.isAlive())
                .as("Publisher thread should complete within 60 s")
                .isFalse();

        // Wait for the subscriber to checkpoint up to the final position.
        long finalPosition = highestEmitted.get();
        awaitCheckpoint(checkpointStore, subscriberId, finalPosition, 30_000L);
        awaitMode(bus2, subscriberId, SubscriberMode.LIVE, 5_000L);

        // ── Assertions ───────────────────────────────────────────────
        List<Long> uniqueSorted = recordedPositions.stream()
                .distinct()
                .sorted()
                .toList();
        List<Long> expected = LongStream.rangeClosed(1L, finalPosition)
                .boxed()
                .toList();
        assertThat(uniqueSorted)
                .as("Deduplicated recorded list must match emitted set 1..%d", finalPosition)
                .isEqualTo(expected);
        assertThat(finalPosition)
                .as("Publisher emitted exactly 1500 events across both phases")
                .isEqualTo(1500L);

        int phase2CaughtUp = caughtUpCount.get() - phase1CaughtUp;
        assertThat(phase2CaughtUp)
                .as("onCaughtUp fires exactly once during phase 2 (single-shot per process per subscriber)")
                .isEqualTo(1);

        bus2.reset();
    }

    /**
     * Polls the checkpoint store until the given subscriber's checkpoint
     * reaches {@code target} or fails after {@code maxWaitMillis}.
     *
     * <p>Uses fixed 50&nbsp;ms polling intervals; does not consult any direct
     * time source.</p>
     */
    private static void awaitCheckpoint(InMemoryCheckpointStore store,
                                         String subscriberId,
                                         long target,
                                         long maxWaitMillis)
            throws InterruptedException {
        long intervals = Math.max(1L, maxWaitMillis / 50L);
        for (long i = 0; i < intervals; i++) {
            if (store.readCheckpoint(subscriberId) >= target) {
                return;
            }
            Thread.sleep(50L);
        }
        throw new AssertionError("Checkpoint for '" + subscriberId
                + "' did not reach " + target + " within " + maxWaitMillis + " ms"
                + " (resting checkpoint " + store.readCheckpoint(subscriberId) + ")");
    }

    /**
     * Polls the bus until the given subscriber reaches the target mode.
     */
    private static void awaitMode(EventBus bus,
                                  String subscriberId,
                                  SubscriberMode target,
                                  long maxWaitMillis) throws InterruptedException {
        long intervals = Math.max(1L, maxWaitMillis / 50L);
        for (long i = 0; i < intervals; i++) {
            try {
                if (bus.subscriberInfo(subscriberId).mode() == target) {
                    return;
                }
            } catch (IllegalArgumentException ignored) {
                // Subscriber not yet registered — keep polling.
            }
            Thread.sleep(50L);
        }
        throw new AssertionError("Subscriber '" + subscriberId
                + "' did not reach " + target + " within " + maxWaitMillis + " ms");
    }
}
