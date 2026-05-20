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
import com.homesynapse.event.bus.SubscriberInfo;
import com.homesynapse.event.bus.SubscriberMode;
import com.homesynapse.event.bus.SubscriberSnapshot;
import com.homesynapse.event.bus.SubscriptionFilter;
import com.homesynapse.event.bus.test.EventBusContractTest;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.UlidFactory;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sustained-load integration test (M3.4b, PLAN-M3-CONSOLIDATED-02 §8.5).
 *
 * <p>Runs a publisher at the MVP §8 sustained target of 100 events/second for
 * the duration configured by the {@code -PsustainedMinutes} property
 * (default 60, dev/CI default 10). The persistence write thread runs the
 * M3.4b {@code ThrottledWriteCoordinator} so each write incurs the Pi-4-
 * equivalent 10 ms baseline plus an occasional 200 ms fsync spike. A single
 * counting subscriber receives every event via the
 * {@code InProcessEventBus}'s active runtime pull loop.</p>
 *
 * <p>The test asserts the durability and capacity invariants that the Pi-4
 * operating envelope demands of M3:</p>
 * <ul>
 *   <li>Subscriber lag stays bounded under sustained load (key signal).</li>
 *   <li>Bus mode remains LIVE — no stuck transition.</li>
 *   <li>WAL file does not exceed the 6 MB LTD-03 ceiling.</li>
 *   <li>Heap stays well within the 256 MB {@code -Xmx} budget.</li>
 *   <li>Checkpoint position converges to the head (within the AMD-38 cadence).</li>
 * </ul>
 *
 * <p>The total event count is reported as a lower-bound sanity check rather
 * than an exact target: with the throttled coordinator (10 ms baseline plus
 * 0.5% × 200 ms spike), realised throughput is naturally slightly below the
 * theoretical 100 ev/s ceiling, and the per-event LIVE checkpoint write
 * shares the single write thread with publishes.</p>
 */
@DisplayName("Sustained load — 100 ev/s with throttled disk")
class Pi4SustainedLoadIT {

    private static final Logger LOG = LoggerFactory.getLogger(Pi4SustainedLoadIT.class);

    /** Subscriber id for the bus-runtime registration. */
    private static final String SUBSCRIBER_ID = "sustained-load-counter";

    /** Rotating entity set size. */
    private static final int ENTITY_COUNT = 50;

    /** Target sustained event rate (MVP §8 sustained baseline). */
    private static final int TARGET_EVENTS_PER_SECOND = 100;

    /** Steady-state lag ceiling under sustained 100 ev/s. */
    private static final long LAG_EVENTS_CEILING = 50L;

    /** AMD-38 APPLIED — checkpoint must be within this many events of head. */
    private static final long CHECKPOINT_FRESHNESS_EVENTS = 200L;

    /** LTD-03 ceiling — WAL file must stay below this size. */
    private static final long WAL_SIZE_CEILING_BYTES = 6L * 1024L * 1024L;

    /** Heap ceiling — comfortable headroom below {@code -Xmx256m}. */
    private static final long HEAP_BUDGET_BYTES = 200L * 1024L * 1024L;

    /** Default duration when {@code sustained.minutes} is not set. */
    private static final int DEFAULT_SUSTAINED_MINUTES = 60;

    /** Periodic progress-log cadence. */
    private static final Duration PROGRESS_LOG_INTERVAL = Duration.ofSeconds(30);

    @TempDir
    Path tempDir;

    private Clock clock;
    private IntegrationTestHarness harness;
    private EventBusContractTest.BusMetricsRecorder metrics;

    Pi4SustainedLoadIT() {
        // Explicit no-arg constructor for -Xlint:all -Werror.
    }

    @BeforeEach
    void setUp() {
        clock = Clock.systemUTC();
        metrics = new EventBusContractTest.BusMetricsRecorder();
        harness = IntegrationTestHarness.startThrottled(
                tempDir.resolve("homesynapse-pi4-sustained.db"),
                clock, metrics, () -> 0);
    }

    @AfterEach
    void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    @Timeout(value = 90, unit = TimeUnit.MINUTES)
    void sustained100EventsPerSecondHoldsTheLagAndDurabilityBudgets()
            throws InterruptedException, SequenceConflictException {

        int sustainedMinutes = Integer.parseInt(
                System.getProperty("sustained.minutes",
                        Integer.toString(DEFAULT_SUSTAINED_MINUTES)));
        Duration workload = Duration.ofMinutes(sustainedMinutes);
        LOG.info("Pi4SustainedLoadIT starting: target={} ev/s, duration={} min, entities={}",
                TARGET_EVENTS_PER_SECOND, sustainedMinutes, ENTITY_COUNT);

        // ── Subscribe a bus-runtime counting subscriber. The counter is the
        //    lightest possible subscriber — onEvent is a single increment —
        //    so we measure the bus + persistence path, not subscriber work.
        AtomicLong delivered = new AtomicLong(0L);
        harness.eventBus().subscribeRuntime(
                new SubscriberInfo(SUBSCRIBER_ID, SubscriptionFilter.all(), false),
                env -> delivered.incrementAndGet());

        // ── Pre-generate the rotating entity set. EntityId allocation is not
        //    on the hot path, but keeping the subjects in one list avoids
        //    repeated UlidFactory.generate calls during the sustained loop.
        List<SubjectRef> subjects = new ArrayList<>(ENTITY_COUNT);
        for (int i = 0; i < ENTITY_COUNT; i++) {
            subjects.add(SubjectRef.entity(new EntityId(UlidFactory.generate(clock))));
        }

        // ── Run the sustained publisher on a single VT for the configured
        //    duration. Throttling pacing is provided by the
        //    ThrottledWriteCoordinator itself — every publish blocks the
        //    publisher VT for ~10 ms baseline plus the occasional spike.
        //    A wall-clock deadline stops the loop.
        AtomicBoolean stopFlag = new AtomicBoolean(false);
        AtomicLong publishedCount = new AtomicLong(0L);
        Instant workloadStart = clock.instant();
        Instant deadline = workloadStart.plus(workload);
        Instant nextProgressLogAt = workloadStart.plus(PROGRESS_LOG_INTERVAL);

        Thread publisher = Thread.ofVirtual().name("pi4-sustained-publisher").start(() -> {
            try {
                int i = 0;
                while (!stopFlag.get() && clock.instant().isBefore(deadline)) {
                    SubjectRef subject = subjects.get(i % ENTITY_COUNT);
                    EventDraft draft = new EventDraft(
                            EventTypes.STATE_REPORTED,
                            1,
                            null,
                            subject,
                            EventPriority.DIAGNOSTIC,
                            EventOrigin.PHYSICAL,
                            new StateReportedEvent(
                                    "level", "v" + i, null, null, null),
                            null,
                            null);
                    EventEnvelope env = harness.eventPublisher().publishRoot(draft);
                    harness.eventBus().notifyEvent(env.globalPosition());
                    publishedCount.incrementAndGet();
                    i++;
                }
            } catch (SequenceConflictException sce) {
                throw new RuntimeException(sce);
            }
        });

        // ── Progress logging from the test VT.
        while (clock.instant().isBefore(deadline) && publisher.isAlive()) {
            Thread.sleep(1000L);
            Instant nowI = clock.instant();
            if (!nowI.isBefore(nextProgressLogAt)) {
                long published = publishedCount.get();
                long deliveredNow = delivered.get();
                LOG.info(
                        "Pi4SustainedLoadIT progress: elapsed={}s published={} delivered={} lag={}",
                        Duration.between(workloadStart, nowI).toSeconds(),
                        published, deliveredNow, published - deliveredNow);
                nextProgressLogAt = nowI.plus(PROGRESS_LOG_INTERVAL);
            }
        }

        stopFlag.set(true);
        publisher.join(Duration.ofSeconds(30).toMillis());
        long totalPublished = publishedCount.get();

        // ── Wait for delivery to catch up. With LIVE per-event delivery, the
        //    final batch should be in the subscriber within a few seconds.
        Instant catchUpDeadline = clock.instant().plus(Duration.ofMinutes(2));
        while (delivered.get() < totalPublished
                && clock.instant().isBefore(catchUpDeadline)) {
            Thread.sleep(100L);
        }
        long totalDelivered = delivered.get();

        // ── Measure WAL size BEFORE close — the lifecycle's stop() flushes
        //    via PRAGMA wal_checkpoint(TRUNCATE) which would zero the file.
        long walSize = walSizeBytes(harness.dbPath().resolveSibling(
                harness.dbPath().getFileName().toString() + "-wal"));

        // ── Force GC and measure heap.
        forceFullGc();
        long heapUsed = ManagementFactory.getMemoryMXBean()
                .getHeapMemoryUsage().getUsed();

        // ── Subscriber snapshot for mode + persisted checkpoint.
        SubscriberSnapshot snapshot = harness.eventBus().subscriberInfo(SUBSCRIBER_ID);
        long persistedCheckpoint = harness.checkpointStore().readCheckpoint(SUBSCRIBER_ID);
        long latestPosition = harness.eventStore().latestPosition();

        // ── Maximum observed lag across the run (windowed max from the recorder).
        long maxLag = metrics.lagRecordsFor(SUBSCRIBER_ID).stream()
                .mapToLong(EventBusContractTest.BusMetricsRecorder.LagRecord::lagEvents)
                .max().orElse(0L);

        LOG.info(
                "Pi4SustainedLoadIT complete: published={} delivered={} lag-max={} "
                        + "wal-bytes={} heap-bytes={} mode={} checkpoint={} latest={}",
                totalPublished, totalDelivered, maxLag,
                walSize, heapUsed, snapshot.mode(),
                persistedCheckpoint, latestPosition);

        // ── Assertions.

        // Sanity: the publisher was not stalled. With the throttled
        // coordinator and per-event LIVE checkpointing sharing the same
        // single writer thread, realised throughput is well below the
        // theoretical 100 ev/s ceiling; the lower bound catches a true
        // stall (publisher VT dead, write coordinator deadlock).
        long targetEvents = (long) TARGET_EVENTS_PER_SECOND * workload.toSeconds();
        long lowerBound = targetEvents / 4;
        assertThat(totalPublished)
                .as("published count is a non-trivial fraction of target=%d"
                        + " under throttled writes", targetEvents)
                .isGreaterThanOrEqualTo(lowerBound);

        assertThat(maxLag)
                .as("max subscriber lag during sustained run (ceiling=%d events)",
                        LAG_EVENTS_CEILING)
                .isLessThanOrEqualTo(LAG_EVENTS_CEILING);

        assertThat(snapshot.mode())
                .as("subscriber mode at end-of-run")
                .isEqualTo(SubscriberMode.LIVE);

        assertThat(walSize)
                .as("WAL file size at end-of-run (LTD-03 ceiling=%d)",
                        WAL_SIZE_CEILING_BYTES)
                .isLessThanOrEqualTo(WAL_SIZE_CEILING_BYTES);

        assertThat(heapUsed)
                .as("heap usage at end-of-run (budget=%d)", HEAP_BUDGET_BYTES)
                .isLessThanOrEqualTo(HEAP_BUDGET_BYTES);

        assertThat(snapshot.dlqDepth())
                .as("no DLQ entries — no supervisor crashes")
                .isZero();
        assertThat(snapshot.crashCount())
                .as("no supervisor crash count")
                .isZero();

        long checkpointLag = latestPosition - persistedCheckpoint;
        assertThat(checkpointLag)
                .as("checkpoint freshness — within AMD-38 cadence "
                        + "(persisted=%d, latest=%d)",
                        persistedCheckpoint, latestPosition)
                .isLessThanOrEqualTo(CHECKPOINT_FRESHNESS_EVENTS);
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

    private static void forceFullGc() throws InterruptedException {
        System.gc();
        Thread.sleep(100L);
        System.gc();
        Thread.sleep(100L);
    }
}
