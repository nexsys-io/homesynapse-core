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
 * D1 spike-pathology integration test (M3.4b, PLAN-M3-CONSOLIDATED-02 §8.6).
 *
 * <p>Targets the WAL-pathology window observed in the 2026-05-15 D1 spike
 * report: at 50 events/second the WAL grows slowly enough that the
 * bounded-reader pattern holds it well below the LTD-03 6 MB ceiling, while
 * the throttled coordinator's 200 ms spikes at 0.5% probability stress the
 * bus's lag-recovery behavior. Over a fixed 30-minute window this is roughly
 * 90 000 publishes with ~450 spikes — enough to characterize spike-induced
 * lag transients and confirm that lag returns to zero between spikes.</p>
 *
 * <p>The duration is intentionally fixed at 30 minutes; the test exists
 * specifically to characterize the D1 window. Use {@code Pi4SustainedLoadIT}
 * (configurable via {@code -PsustainedMinutes}) for the headline throughput
 * verification.</p>
 */
@DisplayName("D1 spike — 50 ev/s for 30 min with 200 ms spikes")
class Pi4D1SpikeIT {

    private static final Logger LOG = LoggerFactory.getLogger(Pi4D1SpikeIT.class);

    private static final String SUBSCRIBER_ID = "spike-counter";

    /** Rotating entity set size. */
    private static final int ENTITY_COUNT = 50;

    /** Target publish rate (D1 pathology window). */
    private static final int TARGET_EVENTS_PER_SECOND = 50;

    /** Fixed workload duration per the D1 window. */
    private static final Duration WORKLOAD = Duration.ofMinutes(30);

    /** Spike-tolerant lag ceiling (looser than sustained because spikes produce transient lag). */
    private static final long LAG_EVENTS_CEILING = 100L;

    /** LTD-03 WAL ceiling. */
    private static final long WAL_SIZE_CEILING_BYTES = 6L * 1024L * 1024L;

    /** Recovery assertion — final lag should drain shortly after the last publish. */
    private static final Duration FINAL_DRAIN_TIMEOUT = Duration.ofSeconds(5);

    /** Periodic progress-log cadence. */
    private static final Duration PROGRESS_LOG_INTERVAL = Duration.ofSeconds(30);

    @TempDir
    Path tempDir;

    private Clock clock;
    private IntegrationTestHarness harness;
    private EventBusContractTest.BusMetricsRecorder metrics;

    Pi4D1SpikeIT() {
        // Explicit no-arg constructor for -Xlint:all -Werror.
    }

    @BeforeEach
    void setUp() {
        clock = Clock.systemUTC();
        metrics = new EventBusContractTest.BusMetricsRecorder();
        harness = IntegrationTestHarness.startThrottled(
                tempDir.resolve("homesynapse-pi4-d1-spike.db"),
                clock, metrics, () -> 0);
    }

    @AfterEach
    void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    @Timeout(value = 45, unit = TimeUnit.MINUTES)
    void d1SpikeWindowKeepsLagRecoverableAndWalBounded()
            throws InterruptedException, SequenceConflictException {

        LOG.info("Pi4D1SpikeIT starting: target={} ev/s, duration={} min",
                TARGET_EVENTS_PER_SECOND, WORKLOAD.toMinutes());

        AtomicLong delivered = new AtomicLong(0L);
        harness.eventBus().subscribeRuntime(
                new SubscriberInfo(SUBSCRIBER_ID, SubscriptionFilter.all(), false),
                env -> delivered.incrementAndGet());

        List<SubjectRef> subjects = new ArrayList<>(ENTITY_COUNT);
        for (int i = 0; i < ENTITY_COUNT; i++) {
            subjects.add(SubjectRef.entity(new EntityId(UlidFactory.generate(clock))));
        }

        AtomicBoolean stopFlag = new AtomicBoolean(false);
        AtomicLong publishedCount = new AtomicLong(0L);
        Instant workloadStart = clock.instant();
        Instant deadline = workloadStart.plus(WORKLOAD);
        Instant nextProgressLogAt = workloadStart.plus(PROGRESS_LOG_INTERVAL);

        // Target inter-event interval = 1000 / 50 = 20ms. The throttled
        // coordinator contributes 10ms baseline per write; we add an
        // additional ~10ms target sleep to hit 50 ev/s overall. The
        // exact sleep is recomputed each iteration against the absolute
        // schedule so that occasional spikes do not progressively drift
        // the publisher off-target.
        final long intervalNanos = 1_000_000_000L / TARGET_EVENTS_PER_SECOND;

        Thread publisher = Thread.ofVirtual().name("pi4-d1-spike-publisher").start(() -> {
            try {
                long startNanos = System.nanoTime();
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
                    long scheduledNanos = startNanos + (long) i * intervalNanos;
                    long sleepNanos = scheduledNanos - System.nanoTime();
                    if (sleepNanos > 0) {
                        TimeUnit.NANOSECONDS.sleep(sleepNanos);
                    }
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (SequenceConflictException sce) {
                throw new RuntimeException(sce);
            }
        });

        while (clock.instant().isBefore(deadline) && publisher.isAlive()) {
            Thread.sleep(1000L);
            Instant nowI = clock.instant();
            if (!nowI.isBefore(nextProgressLogAt)) {
                long published = publishedCount.get();
                long deliveredNow = delivered.get();
                LOG.info(
                        "Pi4D1SpikeIT progress: elapsed={}s published={} delivered={} lag={}",
                        Duration.between(workloadStart, nowI).toSeconds(),
                        published, deliveredNow, published - deliveredNow);
                nextProgressLogAt = nowI.plus(PROGRESS_LOG_INTERVAL);
            }
        }

        stopFlag.set(true);
        publisher.join(Duration.ofSeconds(30).toMillis());
        long totalPublished = publishedCount.get();

        // Recovery assertion — lag should drain to zero within FINAL_DRAIN_TIMEOUT
        // of the final publish. Poll briefly.
        Instant catchUpDeadline = clock.instant().plus(FINAL_DRAIN_TIMEOUT);
        while (delivered.get() < totalPublished
                && clock.instant().isBefore(catchUpDeadline)) {
            Thread.sleep(50L);
        }
        long finalLag = totalPublished - delivered.get();

        long walSize = walSizeBytes(harness.dbPath().resolveSibling(
                harness.dbPath().getFileName().toString() + "-wal"));

        SubscriberSnapshot snapshot = harness.eventBus().subscriberInfo(SUBSCRIBER_ID);

        long maxLag = metrics.lagRecordsFor(SUBSCRIBER_ID).stream()
                .mapToLong(EventBusContractTest.BusMetricsRecorder.LagRecord::lagEvents)
                .max().orElse(0L);
        long parkedCount = metrics.derivedWritesParked().size();

        LOG.info(
                "Pi4D1SpikeIT complete: published={} delivered={} lag-max={} final-lag={} "
                        + "wal-bytes={} parked={} mode={}",
                totalPublished, delivered.get(), maxLag, finalLag,
                walSize, parkedCount, snapshot.mode());

        assertThat(maxLag)
                .as("max subscriber lag during spike window (ceiling=%d events)",
                        LAG_EVENTS_CEILING)
                .isLessThanOrEqualTo(LAG_EVENTS_CEILING);

        assertThat(finalLag)
                .as("lag drained to zero within %s after last publish",
                        FINAL_DRAIN_TIMEOUT)
                .isZero();

        assertThat(walSize)
                .as("WAL file size at end-of-run (LTD-03 ceiling=%d)",
                        WAL_SIZE_CEILING_BYTES)
                .isLessThanOrEqualTo(WAL_SIZE_CEILING_BYTES);

        // Spike-induced parks are rare at 50 ev/s with 0.5% × 200 ms profile.
        // The bus does not exercise the derived-write rate limit in this test
        // (no projection-produced events), so the count should be effectively
        // zero. A small allowance covers any incidental emission.
        assertThat(parkedCount)
                .as("BusWriteParkedEvent count under D1 spike workload")
                .isLessThanOrEqualTo(5L);

        assertThat(snapshot.mode())
                .as("subscriber mode at end-of-run")
                .isEqualTo(SubscriberMode.LIVE);
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
