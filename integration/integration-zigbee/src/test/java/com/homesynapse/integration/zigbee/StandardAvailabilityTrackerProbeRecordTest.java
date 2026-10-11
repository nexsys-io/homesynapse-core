/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import static org.assertj.core.api.Assertions.assertThat;

import com.homesynapse.event.AvailabilityContractDeclaredEvent;
import com.homesynapse.test.TestClock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * AVAIL-API-1 E-3 (AMD-103 R-B) — the tracker's record decisions on an injected monotonic
 * source and clock, both advanced in lockstep by the test: (i) the earliest answered probe
 * after construction records, its window opening at the tracker's start; (ii) the earliest
 * answered probe at or after {@link StandardAvailabilityTracker#RECORD_INTERVAL} on the
 * monotonic source since the device's last record records, with the counts of the probes
 * asked in the window — the recorded answer counted, the counters reset at every record
 * (§4.2's rule: an answer belongs to exactly one window); a miss-then-answer flicker inside
 * the interval records nothing and rides the next record as {@code missed}; a dark verdict
 * stays {@code onTransition}'s. The listener fires AFTER the unlock (E-3 (f)) with the
 * snapshot taken at the decision (E-3 (g)).
 */
@DisplayName("StandardAvailabilityTracker — the probe record (AVAIL-API-1 E-3, AMD-103 R-B)")
final class StandardAvailabilityTrackerProbeRecordTest {

    private static final IEEEAddress MAINS = new IEEEAddress(0x0017880109AB12CDL);
    private static final IEEEAddress LATE = new IEEEAddress(0x00178801AAAA0001L);
    private static final Optional<LinkReading> READING_A =
            Optional.of(new LinkReading(164, -59));
    private static final Optional<LinkReading> READING_B =
            Optional.of(new LinkReading(120, -70));
    /** An arbitrary origin: the window reads the source's DIFFERENCES, never its value. */
    private static final long NANOS_ORIGIN = 5_000_000_000_000L;

    private TestClock clock;
    private long nanos;
    private RecordingListener listener;
    private StandardAvailabilityTracker tracker;
    private Instant constructedAt;

    /** One record as the listener received it. */
    record ProbeRecord(IEEEAddress device, Instant at, Instant windowStart, int answered,
            int missed, LinkReading lastLink, Instant lastLinkAt) { }

    /** The listener: every call whole, plus the E-3 (f) hook that reads the tracker. */
    static final class RecordingListener implements StandardAvailabilityTracker.TransitionListener {
        final List<ProbeRecord> records = new ArrayList<>();
        final List<String> transitions = new ArrayList<>();
        final List<AvailabilityContractDeclaredEvent> contracts = new ArrayList<>();
        Runnable onRecordHook = () -> { };

        RecordingListener() {
        }

        @Override
        public void onTransition(IEEEAddress device, Instant instant, boolean available,
                AvailabilityReason reason, Instant lastSeen, LinkReading lastLink,
                Instant lastLinkAt) {
            transitions.add(device.toHexString() + ":" + available + ":" + reason);
        }

        @Override
        public void onProbeRecord(IEEEAddress device, Instant at, Instant windowStart,
                int answered, int missed, LinkReading lastLink, Instant lastLinkAt) {
            records.add(new ProbeRecord(device, at, windowStart, answered, missed, lastLink,
                    lastLinkAt));
            onRecordHook.run();
        }

        @Override
        public void onContractDeclared(IEEEAddress device, Instant at,
                AvailabilityContractDeclaredEvent contract) {
            contracts.add(contract);
        }
    }

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    StandardAvailabilityTrackerProbeRecordTest() {
    }

    @BeforeEach
    void setUp() {
        clock = TestClock.createDefault();
        nanos = NANOS_ORIGIN;
        listener = new RecordingListener();
    }

    /** A mains tracker (0x01) with no contract: the 60-s floor; the monotonic source is {@code nanos}. */
    private void createTracker(Map<Long, StandardAvailabilityTracker.Seed> seeds) {
        constructedAt = clock.instant();
        tracker = new StandardAvailabilityTracker(clock, () -> nanos,
                device -> 0x01,
                device -> Optional.empty(),
                device -> Optional.empty(),
                seeds,
                listener);
    }

    /** Both sources move together: the wall clock has not stepped (R-B's derivation). */
    private void advance(Duration duration) {
        clock.advance(duration);
        nanos += duration.toNanos();
    }

    private void answer(IEEEAddress device) {
        tracker.recordCommandResult(device, true, clock.instant());
    }

    private void miss(IEEEAddress device) {
        tracker.recordCommandResult(device, false, clock.instant());
    }

    @Test
    @DisplayName("the constant: RECORD_INTERVAL is 3600 s (R-H, a code constant)")
    void recordIntervalIsOneHour() {
        assertThat(StandardAvailabilityTracker.RECORD_INTERVAL).isEqualTo(Duration.ofSeconds(3600));
    }

    @Test
    @DisplayName("E-3 (a): the first answered probe after construction records — windowStart = the "
            + "construction instant's wall value, answered = 1, missed = 0, the last reading kept")
    void firstAnswerRecords_windowOpensAtConstruction() {
        createTracker(Map.of());
        tracker.recordFrame(MAINS, clock.instant(), READING_A);
        Instant frameAt = clock.instant();
        advance(Duration.ofSeconds(10));

        answer(MAINS);

        assertThat(listener.records).hasSize(1);
        ProbeRecord record = listener.records.get(0);
        assertThat(record.device()).isEqualTo(MAINS);
        assertThat(record.at()).isEqualTo(constructedAt.plusSeconds(10));
        assertThat(record.windowStart()).isEqualTo(constructedAt);
        assertThat(record.answered()).isEqualTo(1);
        assertThat(record.missed()).isZero();
        assertThat(record.lastLink()).isEqualTo(READING_A.get());
        assertThat(record.lastLinkAt()).isEqualTo(frameAt);
        assertThat(tracker.probeWindow(MAINS))
                .as("the counters reset at the record")
                .isEqualTo(new StandardAvailabilityTracker.ProbeWindow(0, 0));
    }

    @Test
    @DisplayName("E-3 (b): a second answer 1,800 s after the record records nothing; the window "
            + "counts it — answered 1, missed 0 since the record")
    void answerInsideTheInterval_recordsNothing_countersCarry() {
        createTracker(Map.of());
        tracker.recordFrame(MAINS, clock.instant(), READING_A);
        advance(Duration.ofSeconds(10));
        answer(MAINS);
        advance(Duration.ofSeconds(1800));

        answer(MAINS);

        assertThat(listener.records).hasSize(1);
        assertThat(tracker.probeWindow(MAINS))
                .isEqualTo(new StandardAvailabilityTracker.ProbeWindow(1, 0));
    }

    @Test
    @DisplayName("E-3 (c): an answer at 3,600 s after the record records — answered = 2 (the "
            + "1,800-s answer and this one), missed = 0, windowStart = the previous record's at, "
            + "so at − windowStart = 3,600 s by construction")
    void answerAtTheInterval_records_windowStartIsThePreviousRecord() {
        createTracker(Map.of());
        tracker.recordFrame(MAINS, clock.instant(), READING_A);
        advance(Duration.ofSeconds(10));
        answer(MAINS);
        Instant firstRecordAt = listener.records.get(0).at();
        advance(Duration.ofSeconds(1800));
        answer(MAINS);
        advance(Duration.ofSeconds(1800));

        answer(MAINS);

        assertThat(listener.records).hasSize(2);
        ProbeRecord second = listener.records.get(1);
        assertThat(second.answered()).isEqualTo(2);
        assertThat(second.missed()).isZero();
        assertThat(second.windowStart()).isEqualTo(firstRecordAt);
        assertThat(Duration.between(second.windowStart(), second.at()))
                .isEqualTo(Duration.ofSeconds(3600));
    }

    @Test
    @DisplayName("E-3 (c′): 3,599 s is inside the interval, 3,600 s is at it — 'at or after'")
    void theIntervalIsInclusive() {
        createTracker(Map.of());
        tracker.recordFrame(MAINS, clock.instant(), READING_A);
        answer(MAINS);
        advance(Duration.ofSeconds(3599));
        answer(MAINS);
        assertThat(listener.records).as("3,599 s: inside").hasSize(1);

        advance(Duration.ofSeconds(1));
        answer(MAINS);

        assertThat(listener.records).as("3,600 s: at the interval").hasSize(2);
        assertThat(listener.records.get(1).answered()).isEqualTo(2);
    }

    @Test
    @DisplayName("E-3 (d): a miss then an answer inside the interval records NOTHING (the "
            + "flicker rides the counters); the next record carries missed = 1")
    void missThenAnswerInsideTheInterval_recordsNothing_nextRecordCarriesTheMiss() {
        createTracker(Map.of());
        tracker.recordFrame(MAINS, clock.instant(), READING_A);
        advance(Duration.ofSeconds(10));
        answer(MAINS);
        advance(Duration.ofSeconds(100));
        miss(MAINS);
        advance(Duration.ofSeconds(60));

        answer(MAINS);

        assertThat(listener.records).as("the flicker: no record").hasSize(1);
        assertThat(listener.transitions)
                .as("one miss is not a verdict (K = 2) and the answer is not an edge")
                .containsExactly(MAINS.toHexString() + ":true:FIRST_CONTACT");
        assertThat(tracker.probeWindow(MAINS))
                .isEqualTo(new StandardAvailabilityTracker.ProbeWindow(1, 1));

        advance(Duration.ofSeconds(3600));
        answer(MAINS);

        assertThat(listener.records).hasSize(2);
        assertThat(listener.records.get(1).answered()).isEqualTo(2);
        assertThat(listener.records.get(1).missed()).isEqualTo(1);
    }

    @Test
    @DisplayName("E-3 (e): a dark transition (two misses) emits onTransition only — no record; the "
            + "misses are counted for the window")
    void twoMisses_transitionOnly_noRecord() {
        createTracker(Map.of());
        tracker.recordFrame(MAINS, clock.instant(), READING_A);
        advance(Duration.ofSeconds(61));

        miss(MAINS);
        advance(Duration.ofSeconds(5));
        miss(MAINS);

        assertThat(listener.records).isEmpty();
        assertThat(listener.transitions).containsExactly(
                MAINS.toHexString() + ":true:FIRST_CONTACT",
                MAINS.toHexString() + ":false:PING_TIMEOUT");
        assertThat(tracker.probeWindow(MAINS))
                .isEqualTo(new StandardAvailabilityTracker.ProbeWindow(0, 2));
    }

    @Test
    @DisplayName("E-3 (f): the listener is called AFTER the unlock — a tracker read submitted to a "
            + "second thread from inside onProbeRecord completes within 2 s (a listener under the "
            + "ReentrantLock would leave that thread blocked: the red)")
    void listenerFiresAfterTheUnlock() {
        createTracker(Map.of());
        tracker.recordFrame(MAINS, clock.instant(), READING_A);
        List<String> outcome = new ArrayList<>();
        listener.onRecordHook = () -> {
            CompletableFuture<Boolean> read =
                    CompletableFuture.supplyAsync(() -> tracker.isAvailable(MAINS));
            try {
                outcome.add("completed:" + read.get(2, TimeUnit.SECONDS));
            } catch (TimeoutException timeout) {
                outcome.add("timed out: the lock was held across the listener call");
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        };
        advance(Duration.ofSeconds(10));

        answer(MAINS);

        assertThat(listener.records).hasSize(1);
        assertThat(outcome).containsExactly("completed:true");
    }

    @Test
    @DisplayName("E-3 (g): the snapshot rule — the record carries the reading at the decision; a "
            + "frame after the record does not change what was handed out, and the next record "
            + "carries the newer reading")
    void snapshotAtTheDecision() {
        createTracker(Map.of());
        tracker.recordFrame(MAINS, clock.instant(), READING_A);
        Instant frameA = clock.instant();
        advance(Duration.ofSeconds(10));
        answer(MAINS);
        advance(Duration.ofSeconds(5));
        tracker.recordFrame(MAINS, clock.instant(), READING_B);
        Instant frameB = clock.instant();

        ProbeRecord first = listener.records.get(0);
        assertThat(first.lastLink()).isEqualTo(READING_A.get());
        assertThat(first.lastLinkAt()).isEqualTo(frameA);
        assertThat(tracker.lastLink(MAINS)).contains(READING_B.get());

        advance(Duration.ofSeconds(3600));
        answer(MAINS);

        ProbeRecord second = listener.records.get(1);
        assertThat(second.lastLink()).isEqualTo(READING_B.get());
        assertThat(second.lastLinkAt()).isEqualTo(frameB);
    }

    @Test
    @DisplayName("E-3 (h): a device first seen after construction opens its window at the "
            + "tracker's start too (R-B: the opening window starts at the tracker's start)")
    void lateDevice_openingWindowStartsAtTheTrackersStart() {
        createTracker(Map.of());
        advance(Duration.ofSeconds(100));
        tracker.recordFrame(LATE, clock.instant(), READING_A);
        advance(Duration.ofSeconds(100));

        answer(LATE);

        assertThat(listener.records).hasSize(1);
        assertThat(listener.records.get(0).windowStart()).isEqualTo(constructedAt);
        assertThat(listener.records.get(0).at()).isEqualTo(constructedAt.plusSeconds(200));
    }

    @Test
    @DisplayName("E-3 (i): a seeded device's first answer is its opening record (no record yet for "
            + "the device) and a frame is never a probe: frames alone record nothing")
    void seededDevice_firstAnswerRecords_framesRecordNothing() {
        createTracker(Map.of(MAINS.value(),
                new StandardAvailabilityTracker.Seed(true, constructedAtOrNull())));
        advance(Duration.ofSeconds(30));
        tracker.recordFrame(MAINS, clock.instant(), READING_A);
        tracker.recordFrame(MAINS, clock.instant(), READING_A);
        assertThat(listener.records).as("frames are evidence, not probes").isEmpty();
        advance(Duration.ofSeconds(70));

        answer(MAINS);

        assertThat(listener.records).hasSize(1);
        assertThat(listener.records.get(0).windowStart()).isEqualTo(constructedAt);
        assertThat(listener.records.get(0).answered()).isEqualTo(1);
    }

    @Test
    @DisplayName("E-3 (j): a successful command result on an UNTRACKED device records with the "
            + "window at the tracker's start — the window never reads the source's absolute value")
    void monotonicOriginIsNeverAWallInstant() {
        createTracker(Map.of());
        advance(Duration.ofSeconds(42));

        answer(MAINS);

        assertThat(listener.records).hasSize(1);
        assertThat(listener.records.get(0).windowStart()).isEqualTo(constructedAt);
        assertThat(listener.records.get(0).at()).isEqualTo(constructedAt.plusSeconds(42));
    }

    private Instant constructedAtOrNull() {
        return clock.instant().minusSeconds(3600);
    }
}
