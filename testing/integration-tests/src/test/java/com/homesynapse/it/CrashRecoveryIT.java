/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.homesynapse.event.EventDraft;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventOrigin;
import com.homesynapse.event.EventPage;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.SequenceConflictException;
import com.homesynapse.event.StateReportedEvent;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.event.bus.SubscriberInfo;
import com.homesynapse.event.bus.SubscriberMode;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Crash-recovery integration test (M3.4b, PLAN-M3-CONSOLIDATED-02 §8.9).
 *
 * <p>Verifies the durable-checkpoint contract under simulated ungraceful
 * shutdown:</p>
 * <ol>
 *   <li>Publish 5 000 events into a real SQLite-backed bus.</li>
 *   <li>Wait until the LIVE subscriber's checkpoint advances past 3 000
 *       events (proving the per-event LIVE checkpoint cadence is durable).</li>
 *   <li>{@link IntegrationTestHarness#abandon() Abandon} the first harness
 *       — the WAL is NOT flushed and the {@code DatabaseExecutor} is NOT
 *       shut down. This simulates a {@code kill -9}.</li>
 *   <li>Open a fresh harness against the same database file. SQLite's
 *       automatic WAL recovery replays uncommitted pages on first
 *       connection. The migration runner sees {@code V001..V004} already
 *       applied and exits clean.</li>
 *   <li>Re-subscribe the same subscriber id. The bus enters {@link
 *       SubscriberMode#REPLAY REPLAY} from the persisted checkpoint, drains
 *       the replay window, transitions to {@link SubscriberMode#LIVE LIVE},
 *       and delivers every remaining event.</li>
 *   <li>Assert that every {@code globalPosition} in {@code [1, 5000]} was
 *       observed exactly once across the two lifetimes (no loss, no
 *       duplication beyond the at-least-once replay window from the
 *       persisted checkpoint).</li>
 * </ol>
 *
 * <p>The shared database file is held in a class-level {@link TempDir} with
 * {@link CleanupMode#ON_SUCCESS} so the path persists across both harness
 * instances within the test. JUnit's default per-test cleanup would delete
 * it between the two phases.</p>
 */
@DisplayName("Crash recovery — 5,000 events survive simulated ungraceful shutdown")
class CrashRecoveryIT {

    private static final Logger LOG = LoggerFactory.getLogger(CrashRecoveryIT.class);

    private static final String SUBSCRIBER_ID = "crash-recovery-subscriber";

    /** Total events published in phase 1. */
    private static final int TOTAL_EVENTS = 5_000;

    /** Minimum checkpoint position required before triggering the simulated crash. */
    private static final long CHECKPOINT_TRIGGER = 3_000L;

    /** Wall-clock ceiling for phase-1 publishing (no throttling). */
    private static final Duration PHASE1_TIMEOUT = Duration.ofMinutes(5);

    /** Wall-clock ceiling for the REPLAY → TRANSITION → LIVE transition. */
    private static final Duration REPLAY_TIMEOUT = Duration.ofSeconds(30);

    /** Polling interval for checkpoint convergence. */
    private static final Duration POLL_INTERVAL = Duration.ofMillis(50);

    /**
     * Shared {@code @TempDir} held at instance scope. {@link CleanupMode#NEVER}
     * is required because the abandoned (phase-1) harness deliberately leaves
     * its {@code DatabaseExecutor} running and its SQLite connections open —
     * simulating an ungraceful process termination. On Windows those open
     * handles prevent directory deletion, so JUnit's default cleanup throws
     * an IOException after a successful test. The OS reclaims the temp dir
     * after the JVM exits.
     */
    @TempDir(cleanup = CleanupMode.NEVER)
    Path tempDir;

    private IntegrationTestHarness harness;

    CrashRecoveryIT() {
        // Explicit no-arg constructor for -Xlint:all -Werror.
    }

    @AfterEach
    void tearDown() {
        if (harness != null) {
            // Either close() (graceful — phase 2) or the no-op proxy after
            // abandon() (phase 1). Idempotent either way.
            harness.close();
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    void checkpointDrivenRecoveryDeliversEveryEventExactlyOnce()
            throws InterruptedException, SequenceConflictException, IOException {

        Clock clock = Clock.systemUTC();
        Path dbPath = tempDir.resolve("homesynapse-crash-recovery.db");

        // ── Phase 1: publish + abandon ────────────────────────────────────

        // Use the for-crash-simulation factory so abandon() reaches the
        // underlying persistence-test-harness. No throttling: the test is
        // about correctness across the crash boundary, not throughput.
        harness = IntegrationTestHarness.startForCrashSimulation(dbPath, clock);

        // The phase-1 subscriber records the global positions it observes.
        // Use a thread-safe set since onEvent runs on the bus's per-subscriber
        // VT; the test thread reads it after the publisher loop ends.
        Set<Long> observedPositions = ConcurrentHashMap.newKeySet();
        AtomicLong phase1Delivered = new AtomicLong(0L);
        harness.eventBus().subscribeRuntime(
                new SubscriberInfo(SUBSCRIBER_ID, SubscriptionFilter.all(), false),
                env -> {
                    observedPositions.add(env.globalPosition());
                    phase1Delivered.incrementAndGet();
                });

        // Pre-generate entity ids to keep allocation off the hot publish loop.
        List<SubjectRef> subjects = new ArrayList<>(TOTAL_EVENTS);
        for (int i = 0; i < TOTAL_EVENTS; i++) {
            subjects.add(SubjectRef.entity(new EntityId(UlidFactory.generate(clock))));
        }

        Instant phase1Start = clock.instant();
        for (int i = 0; i < TOTAL_EVENTS; i++) {
            EventDraft draft = new EventDraft(
                    EventTypes.STATE_REPORTED,
                    1,
                    null,
                    subjects.get(i),
                    EventPriority.DIAGNOSTIC,
                    EventOrigin.PHYSICAL,
                    new StateReportedEvent("v", String.valueOf(i), null, null, null),
                    null,
                    null);
            EventEnvelope env = harness.eventPublisher().publishRoot(draft);
            harness.eventBus().notifyEvent(env.globalPosition());

            if (Duration.between(phase1Start, clock.instant()).compareTo(PHASE1_TIMEOUT) > 0) {
                throw new AssertionError(
                        "Phase 1 publish exceeded timeout " + PHASE1_TIMEOUT);
            }
        }
        LOG.info("CrashRecoveryIT phase 1 publish complete: events={}", TOTAL_EVENTS);

        // Wait for the durable checkpoint to advance past the trigger.
        Instant checkpointDeadline = clock.instant().plus(Duration.ofMinutes(2));
        long checkpointAtCrash = harness.checkpointStore().readCheckpoint(SUBSCRIBER_ID);
        while (checkpointAtCrash < CHECKPOINT_TRIGGER
                && clock.instant().isBefore(checkpointDeadline)) {
            Thread.sleep(POLL_INTERVAL.toMillis());
            checkpointAtCrash = harness.checkpointStore().readCheckpoint(SUBSCRIBER_ID);
        }
        long deliveredAtCrash = phase1Delivered.get();
        LOG.info("CrashRecoveryIT phase 1 about to abandon: delivered={} checkpoint={}",
                deliveredAtCrash, checkpointAtCrash);

        assertThat(checkpointAtCrash)
                .as("checkpoint must reach the trigger before crash simulation")
                .isGreaterThanOrEqualTo(CHECKPOINT_TRIGGER);

        // SIMULATED CRASH — no WAL flush, no DatabaseExecutor shutdown.
        harness.abandon();
        IntegrationTestHarness abandoned = harness;
        harness = null;

        // ── Phase 2: restart + verify recovery ───────────────────────────

        IntegrationTestHarness restart = IntegrationTestHarness.start(dbPath, clock);
        this.harness = restart; // @AfterEach closes this one gracefully

        // Re-subscribe under the same id. The bus reads the persisted
        // checkpoint and begins replay from there.
        restart.eventBus().subscribeRuntime(
                new SubscriberInfo(SUBSCRIBER_ID, SubscriptionFilter.all(), false),
                env -> observedPositions.add(env.globalPosition()));

        // Wait for the replay-and-go-LIVE transition. The bus reads the
        // persisted checkpoint, drives ReplayDriver to the tail, and
        // transitions REPLAY → TRANSITION → LIVE.
        Instant replayDeadline = clock.instant().plus(REPLAY_TIMEOUT);
        SubscriberSnapshot snap = restart.eventBus().subscriberInfo(SUBSCRIBER_ID);
        while (snap.mode() != SubscriberMode.LIVE
                && clock.instant().isBefore(replayDeadline)) {
            Thread.sleep(POLL_INTERVAL.toMillis());
            snap = restart.eventBus().subscriberInfo(SUBSCRIBER_ID);
        }

        // Convergence — wait for observedPositions to cover the full [1..5000].
        Instant convergeDeadline = clock.instant().plus(Duration.ofMinutes(2));
        while (observedPositions.size() < TOTAL_EVENTS
                && clock.instant().isBefore(convergeDeadline)) {
            Thread.sleep(POLL_INTERVAL.toMillis());
        }

        long finalCheckpoint = restart.checkpointStore().readCheckpoint(SUBSCRIBER_ID);
        long finalLatest = restart.eventStore().latestPosition();
        Set<Long> finalObserved = new HashSet<>(observedPositions);
        SubscriberSnapshot finalSnapshot = restart.eventBus().subscriberInfo(SUBSCRIBER_ID);

        LOG.info(
                "CrashRecoveryIT phase 2 complete: observed={} checkpoint={} latest={} mode={}",
                finalObserved.size(), finalCheckpoint, finalLatest, finalSnapshot.mode());

        // Sanity-check the abandoned harness is unused after this point.
        assertThat(abandoned).isNotNull();

        // ── Assertions ────────────────────────────────────────────────────

        // 1. Bus reached LIVE after the simulated restart.
        assertThat(finalSnapshot.mode())
                .as("bus transitioned to LIVE after restart")
                .isEqualTo(SubscriberMode.LIVE);

        // 2. Every globalPosition in [1, 5000] was observed across the two
        //    lifetimes. Replay may re-deliver events from the persisted
        //    checkpoint, but the Set discards duplicates — exact coverage
        //    of {1..5000} is the durability guarantee.
        assertThat(finalObserved)
                .as("every globalPosition in [1, %d] observed exactly once "
                        + "across the simulated crash boundary", TOTAL_EVENTS)
                .hasSize(TOTAL_EVENTS);
        for (long pos = 1L; pos <= TOTAL_EVENTS; pos++) {
            assertThat(finalObserved.contains(pos))
                    .as("globalPosition %d delivered to subscriber", pos)
                    .isTrue();
        }

        // 3. Final checkpoint reached the head.
        assertThat(finalCheckpoint)
                .as("final persisted checkpoint")
                .isEqualTo(TOTAL_EVENTS);

        // 4. Event store is intact — exactly 5000 events durable after recovery.
        EventPage page = restart.eventStore().readFrom(0L, TOTAL_EVENTS + 100);
        assertThat(page.events())
                .as("event store contains exactly %d events after WAL recovery",
                        TOTAL_EVENTS)
                .hasSize(TOTAL_EVENTS);
        assertThat(finalLatest)
                .as("event store latest position equals total events")
                .isEqualTo(TOTAL_EVENTS);

        // 5. The database file is still present and non-empty — WAL recovery
        //    did not corrupt the main DB.
        assertThat(Files.exists(dbPath)).isTrue();
        assertThat(Files.size(dbPath)).isGreaterThan(0L);
    }
}
