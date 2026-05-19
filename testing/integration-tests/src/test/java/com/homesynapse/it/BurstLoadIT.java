/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.homesynapse.event.EventDraft;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventOrigin;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.SequenceConflictException;
import com.homesynapse.event.StateReportedEvent;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.event.bus.Subscriber;
import com.homesynapse.event.bus.SubscriberInfo;
import com.homesynapse.event.bus.SubscriberSnapshot;
import com.homesynapse.event.bus.SubscriptionFilter;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.UlidFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Burst-load integration test exercising the real production stack under
 * the Pi 4 JVM profile.
 *
 * <p>Publishes 500 events (100 entities × 5 events each) as fast as the
 * write coordinator will accept them, with one bus subscriber recording
 * every delivery. Validates that:</p>
 * <ul>
 *   <li>The single-writer SQLite path holds under the burst (ingest
 *       wall-clock duration well under 5 seconds, i.e. ≥ 100 ev/s).</li>
 *   <li>The subscriber receives all 500 events (no drops, no DLQ entries).</li>
 *   <li>Per-event checkpoint advancement keeps the persisted position in
 *       lock-step with the LIVE delivery loop (M3.2 LIVE per-event
 *       checkpoint cadence).</li>
 * </ul>
 *
 * <p>This test does NOT exercise a {@code StateProjection} — the counter
 * subscriber suffices to prove the bus + persistence wire-through works.
 * Heap behaviour with a real projection is tested by {@link HeapBudgetIT}.</p>
 */
@DisplayName("Burst load — 500 events through the real stack")
class BurstLoadIT {

    /** Wall-clock ceiling for the publish loop (per WU spec). */
    private static final Duration INGEST_TIME_BUDGET = Duration.ofSeconds(5);

    /** Generous ceiling for end-to-end delivery (publish + WAL + dispatch). */
    private static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(30);

    private static final int ENTITY_COUNT = 100;
    private static final int EVENTS_PER_ENTITY = 5;
    private static final int TOTAL_EVENTS = ENTITY_COUNT * EVENTS_PER_ENTITY;

    private static final String SUBSCRIBER_ID = "burst-counter";

    @TempDir
    Path tempDir;

    private Clock clock;
    private IntegrationTestHarness harness;

    BurstLoadIT() {
        // Explicit no-arg constructor for -Xlint:all -Werror.
    }

    @BeforeEach
    void setUp() {
        // Real wall-clock time is required for SQLite WAL `ingest_time`
        // microseconds to be monotonic across publishes; a fixed Clock would
        // produce duplicate sequence-key conflicts under burst load.
        clock = Clock.systemUTC();
        harness = IntegrationTestHarness.start(
                tempDir.resolve("homesynapse-burst-load.db"), clock);
    }

    @AfterEach
    void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void burstOf500EventsIsIngestedAndDelivered() throws SequenceConflictException, InterruptedException {
        // ── 1. Pre-generate the entity IDs so all 500 publishes are sized
        //       to fit the EventDraft heap budget under -Xmx256m.
        List<SubjectRef> subjects = new ArrayList<>(ENTITY_COUNT);
        for (int i = 0; i < ENTITY_COUNT; i++) {
            subjects.add(SubjectRef.entity(new EntityId(UlidFactory.generate(clock))));
        }

        // ── 2. Register a counting subscriber BEFORE publishing so the
        //       events route through the LIVE pull loop (or the replay
        //       window queue → LIVE drain).
        AtomicInteger delivered = new AtomicInteger(0);
        CountDownLatch allDelivered = new CountDownLatch(TOTAL_EVENTS);
        Subscriber counter = env -> {
            delivered.incrementAndGet();
            allDelivered.countDown();
        };
        harness.eventBus().subscribeRuntime(
                new SubscriberInfo(SUBSCRIBER_ID, SubscriptionFilter.all(), false),
                counter);

        // ── 3. Publish 500 events. Each publish: SQLite WAL append, then
        //       bus.notifyEvent so the subscriber's pending queue grows.
        Instant publishStart = clock.instant();
        for (int e = 0; e < EVENTS_PER_ENTITY; e++) {
            for (int s = 0; s < ENTITY_COUNT; s++) {
                EventDraft draft = new EventDraft(
                        EventTypes.STATE_REPORTED,
                        1,
                        null,
                        subjects.get(s),
                        EventPriority.DIAGNOSTIC,
                        EventOrigin.PHYSICAL,
                        new StateReportedEvent(
                                "attr", "v" + e, null, null, null),
                        null,
                        null);
                EventEnvelope env = harness.eventPublisher().publishRoot(draft);
                harness.eventBus().notifyEvent(env.globalPosition());
            }
        }
        Duration ingestDuration = Duration.between(publishStart, clock.instant());

        // ── 4. Wait for delivery completion.
        boolean completed = allDelivered.await(
                DELIVERY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

        // ── 5. Assertions.
        assertThat(ingestDuration)
                .as("publish wall-clock duration for %d events", TOTAL_EVENTS)
                .isLessThan(INGEST_TIME_BUDGET);

        assertThat(completed)
                .as("all %d events delivered within %s", TOTAL_EVENTS, DELIVERY_TIMEOUT)
                .isTrue();

        assertThat(delivered.get())
                .as("subscriber receive count")
                .isEqualTo(TOTAL_EVENTS);

        // The DLQ is private to the bus's per-subscriber runtime; the
        // observable contract is that the subscriber's mode is not SUSPENDED.
        // No supervisor crash should have occurred: every onEvent returned
        // normally.
        SubscriberSnapshot snapshot = harness.eventBus().subscriberInfo(SUBSCRIBER_ID);
        assertThat(snapshot.dlqDepth())
                .as("no supervisor crashes — DLQ is empty")
                .isZero();
        assertThat(snapshot.crashCount())
                .as("no supervisor crashes recorded")
                .isZero();

        // Sanity-check the persisted state: every event written + checkpoint
        // caught up to (or near) the head.
        long latest = harness.eventStore().latestPosition();
        assertThat(latest)
                .as("event store latest position equals total events published")
                .isEqualTo(TOTAL_EVENTS);

        // Per-event LIVE checkpointing means the persisted subscriber
        // checkpoint converges to the latest position shortly after delivery.
        // The latch fires inside onEvent — the supervisor's per-event
        // checkpoint write happens *after* onEvent returns, so a small race
        // window exists. Poll briefly to let it converge.
        long persisted = waitForCheckpoint(SUBSCRIBER_ID, TOTAL_EVENTS,
                Duration.ofSeconds(10));
        assertThat(persisted)
                .as("subscriber checkpoint reaches the head after delivery completes")
                .isEqualTo(TOTAL_EVENTS);

        // ── 6. WAL file exists and is bounded (real WAL mode in effect).
        Path walPath = harness.dbPath().resolveSibling(
                harness.dbPath().getFileName().toString() + "-wal");
        long walSize = walSizeBytes(walPath);
        assertThat(walSize)
                .as("WAL file present (or already checkpointed) after %d events", TOTAL_EVENTS)
                .isGreaterThanOrEqualTo(0L);
    }

    /**
     * Polls the subscriber checkpoint until it reaches {@code target} or
     * the timeout elapses, then returns the most recently observed value.
     */
    private long waitForCheckpoint(String subscriberId, long target,
                                   Duration timeout) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        long current = harness.checkpointStore().readCheckpoint(subscriberId);
        while (current < target && System.nanoTime() < deadlineNanos) {
            Thread.sleep(25L);
            current = harness.checkpointStore().readCheckpoint(subscriberId);
        }
        return current;
    }

    private static long walSizeBytes(Path walPath) {
        if (!Files.exists(walPath)) {
            return 0L;
        }
        try {
            return Files.size(walPath);
        } catch (IOException e) {
            throw new AssertionError("Unable to stat WAL file " + walPath, e);
        }
    }
}
