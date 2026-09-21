/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import com.homesynapse.test.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link StandardAvailabilityTracker} tests (Doc 08 §3.11 availability +
 * §8.1 M-1 restart initialization): power-source-aware silence timeouts,
 * transition listener firing, and the planned-restart rule — initialization
 * from persisted pre-restart state emits NO false unavailable→available
 * transitions. The M9.4b §6.10 N-5 posture pins: battery UNLESS the ZCL Basic
 * PowerSource value is a mains class (0x01/0x02) — UNKNOWN (0x00) and exotic
 * values get the 25 h battery-conservative window, never the 10-min
 * active-ping regime.
 */
class StandardAvailabilityTrackerTest {

    private static final IEEEAddress BATTERY_DEVICE =
            new IEEEAddress(0x00124B0012345678L);
    private static final IEEEAddress MAINS_DEVICE =
            new IEEEAddress(0x0017880109AB12CDL);
    private static final IEEEAddress THREE_PHASE_DEVICE =
            new IEEEAddress(0x00158D0001AB34EFL);
    private static final IEEEAddress DC_DEVICE =
            new IEEEAddress(0x00124B00AA55AA55L);
    private static final IEEEAddress UNKNOWN_DEVICE =
            new IEEEAddress(0x8CF681FFFE12AB34L);
    /**
     * The reading the availability tests do not care about (LINK-READ): the
     * bench-measured SNZB-03P pair (the walk-test fixture's 160–164 / −59 dBm).
     */
    private static final Optional<LinkReading> ANY_LINK =
            Optional.of(new LinkReading(164, -59));

    private TestClock clock;
    private Map<Long, Integer> powerSources;
    private List<String> transitions;
    private StandardAvailabilityTracker tracker;

    @BeforeEach
    void setUp() {
        clock = TestClock.createDefault();
        powerSources = new HashMap<>();
        // ZCL Basic PowerSource table values (N-5: only 0x01/0x02 are mains).
        powerSources.put(BATTERY_DEVICE.value(), 0x03);     // battery
        powerSources.put(MAINS_DEVICE.value(), 0x01);       // mains single phase
        powerSources.put(THREE_PHASE_DEVICE.value(), 0x02); // mains 3 phase
        powerSources.put(DC_DEVICE.value(), 0x04);          // DC source
        powerSources.put(UNKNOWN_DEVICE.value(), 0x00);     // unknown
        transitions = new ArrayList<>();
    }

    private void createTracker(
            Map<Long, StandardAvailabilityTracker.Seed> persisted) {
        tracker = new StandardAvailabilityTracker(clock,
                device -> powerSources.getOrDefault(device.value(), 0),
                persisted,
                (device, available) ->
                        transitions.add(device.toHexString() + ":" + available));
    }

    /** A seed entry (WU-AVAIL-SEED DP-1): both components nullable. */
    private static StandardAvailabilityTracker.Seed seed(Boolean available,
            Instant lastEvidenceAt) {
        return new StandardAvailabilityTracker.Seed(available, lastEvidenceAt);
    }

    @Test
    @DisplayName("a new device transitions to available on FIRST_CONTACT")
    void firstContactTransitions() {
        createTracker(Map.of());

        tracker.recordFrame(BATTERY_DEVICE, clock.instant(), ANY_LINK);

        assertThat(tracker.isAvailable(BATTERY_DEVICE)).isTrue();
        assertThat(tracker.lastReason(BATTERY_DEVICE))
                .isEqualTo(AvailabilityReason.FIRST_CONTACT);
        assertThat(transitions).hasSize(1);
    }

    @Test
    @DisplayName("restart-init: persisted AVAILABLE state carries forward with NO transition")
    void restartInitCarriesForwardSilently() {
        createTracker(Map.of(BATTERY_DEVICE.value(), seed(true, clock.instant())));

        assertThat(tracker.isAvailable(BATTERY_DEVICE)).isTrue();
        assertThat(transitions)
                .as("a planned restart must not produce a false "
                        + "unavailable→available cascade")
                .isEmpty();

        tracker.recordFrame(BATTERY_DEVICE, clock.instant(), ANY_LINK);

        assertThat(transitions)
                .as("the first post-restart frame confirms, never transitions")
                .isEmpty();
    }

    @Test
    @DisplayName("restart-init: persisted UNAVAILABLE stays unavailable until a frame arrives")
    void restartInitUnavailableUntilFrame() {
        createTracker(Map.of(BATTERY_DEVICE.value(), seed(false, null)));

        assertThat(tracker.isAvailable(BATTERY_DEVICE)).isFalse();

        tracker.recordFrame(BATTERY_DEVICE, clock.instant(), ANY_LINK);

        assertThat(tracker.isAvailable(BATTERY_DEVICE)).isTrue();
        assertThat(transitions).hasSize(1);
        assertThat(tracker.lastReason(BATTERY_DEVICE))
                .isEqualTo(AvailabilityReason.FRAME_RECEIVED);
    }

    @Test
    @DisplayName("battery devices go unavailable after 25 h of silence")
    void batterySilenceTimeout() {
        createTracker(Map.of());
        tracker.recordFrame(BATTERY_DEVICE, clock.instant(), ANY_LINK);
        transitions.clear();

        clock.advance(Duration.ofHours(24));
        assertThat(tracker.evaluateTimeouts())
                .as("N-5: battery devices never enter the active-ping regime")
                .isEmpty();
        assertThat(tracker.isAvailable(BATTERY_DEVICE))
                .as("24 h is within the 25 h battery window")
                .isTrue();

        clock.advance(Duration.ofHours(2));
        tracker.evaluateTimeouts();

        assertThat(tracker.isAvailable(BATTERY_DEVICE)).isFalse();
        assertThat(tracker.lastReason(BATTERY_DEVICE))
                .isEqualTo(AvailabilityReason.SILENCE_TIMEOUT);
        assertThat(transitions).containsExactly(
                BATTERY_DEVICE.toHexString() + ":false");
    }

    @Test
    @DisplayName("mains devices become ping candidates after 10 min — not yet unavailable")
    void mainsSilenceYieldsPingCandidate() {
        createTracker(Map.of());
        tracker.recordFrame(MAINS_DEVICE, clock.instant(), ANY_LINK);
        transitions.clear();

        clock.advance(Duration.ofMinutes(11));
        List<IEEEAddress> pingCandidates = tracker.evaluateTimeouts();

        assertThat(pingCandidates).containsExactly(MAINS_DEVICE);
        assertThat(tracker.isAvailable(MAINS_DEVICE))
                .as("the active ping (M9.4 ZCL read) decides; silence alone "
                        + "does not mark mains devices offline")
                .isTrue();
    }

    @Test
    @DisplayName("N-5: mains 3-phase (0x02) gets the mains ping posture")
    void threePhaseMainsYieldsPingCandidate() {
        createTracker(Map.of());
        tracker.recordFrame(THREE_PHASE_DEVICE, clock.instant(), ANY_LINK);
        transitions.clear();

        clock.advance(Duration.ofMinutes(11));

        assertThat(tracker.evaluateTimeouts())
                .containsExactly(THREE_PHASE_DEVICE);
        assertThat(tracker.isAvailable(THREE_PHASE_DEVICE)).isTrue();
    }

    @Test
    @DisplayName("N-5: UNKNOWN power source (0x00) gets the 25 h battery-conservative window")
    void unknownPowerSourceIsBatteryConservative() {
        createTracker(Map.of());
        tracker.recordFrame(UNKNOWN_DEVICE, clock.instant(), ANY_LINK);
        transitions.clear();

        clock.advance(Duration.ofMinutes(11));
        assertThat(tracker.evaluateTimeouts())
                .as("a possibly-sleepy device must never be false-offlined "
                        + "by the 10-min active-ping regime")
                .isEmpty();
        assertThat(tracker.isAvailable(UNKNOWN_DEVICE)).isTrue();

        clock.advance(Duration.ofHours(26));
        tracker.evaluateTimeouts();

        assertThat(tracker.isAvailable(UNKNOWN_DEVICE)).isFalse();
        assertThat(tracker.lastReason(UNKNOWN_DEVICE))
                .isEqualTo(AvailabilityReason.SILENCE_TIMEOUT);
        assertThat(transitions).containsExactly(
                UNKNOWN_DEVICE.toHexString() + ":false");
    }

    @Test
    @DisplayName("N-5: an exotic power source (0x04 DC) falls to battery-conservative")
    void dcPowerSourceIsBatteryConservative() {
        createTracker(Map.of());
        tracker.recordFrame(DC_DEVICE, clock.instant(), ANY_LINK);
        transitions.clear();

        clock.advance(Duration.ofMinutes(11));
        assertThat(tracker.evaluateTimeouts())
                .as("battery posture UNLESS mains — not battery-equality")
                .isEmpty();
        assertThat(tracker.isAvailable(DC_DEVICE)).isTrue();

        clock.advance(Duration.ofHours(26));
        tracker.evaluateTimeouts();

        assertThat(tracker.isAvailable(DC_DEVICE)).isFalse();
        assertThat(tracker.lastReason(DC_DEVICE))
                .isEqualTo(AvailabilityReason.SILENCE_TIMEOUT);
    }

    @Test
    @DisplayName("a failed command result after repeated failures marks unavailable via PING_TIMEOUT")
    void failedPingMarksUnavailable() {
        createTracker(Map.of());
        tracker.recordFrame(MAINS_DEVICE, clock.instant(), ANY_LINK);
        transitions.clear();

        tracker.recordCommandResult(MAINS_DEVICE, false, clock.instant());

        assertThat(tracker.isAvailable(MAINS_DEVICE)).isFalse();
        assertThat(tracker.lastReason(MAINS_DEVICE))
                .isEqualTo(AvailabilityReason.PING_TIMEOUT);
    }

    @Test
    @DisplayName("a successful command confirms reachability")
    void successfulCommandConfirms() {
        createTracker(Map.of(MAINS_DEVICE.value(), seed(false, null)));

        tracker.recordCommandResult(MAINS_DEVICE, true, clock.instant());

        assertThat(tracker.isAvailable(MAINS_DEVICE)).isTrue();
        assertThat(tracker.lastReason(MAINS_DEVICE))
                .isEqualTo(AvailabilityReason.PING_SUCCESS);
    }

    // ── WU-AVAIL-SEED DP-1: the sidecar seed enters devices into tracking ──
    // Seed semantics under test: a seeded value never counts as fresh
    // evidence, seeding publishes nothing, the timeout clock rides the
    // persisted instant (never boot time), and unknown recency is infinitely
    // stale (never-false-ALIVE — false-ALIVE-avoidance wins every tie).

    @Test
    @DisplayName("DP-1/T-1: a seeded mains device with stale persisted evidence is a "
            + "ping candidate at the FIRST evaluation")
    void seededStaleMains_pingCandidateAtFirstEvaluation() {
        createTracker(Map.of(MAINS_DEVICE.value(),
                seed(true, clock.instant().minus(Duration.ofHours(3)))));

        assertThat(tracker.evaluateTimeouts()).containsExactly(MAINS_DEVICE);
        assertThat(transitions)
                .as("candidacy is not a verdict — the ping decides")
                .isEmpty();
    }

    @Test
    @DisplayName("DP-1/T-2: a seeded battery device whose persisted evidence is older "
            + "than 25 h times out at the FIRST evaluation — SILENCE_TIMEOUT, one "
            + "listener call")
    void seededStaleBattery_timesOutAtFirstEvaluation() {
        createTracker(Map.of(BATTERY_DEVICE.value(),
                seed(true, clock.instant().minus(Duration.ofHours(26)))));

        assertThat(tracker.evaluateTimeouts()).isEmpty();

        assertThat(tracker.isAvailable(BATTERY_DEVICE)).isFalse();
        assertThat(tracker.lastReason(BATTERY_DEVICE))
                .isEqualTo(AvailabilityReason.SILENCE_TIMEOUT);
        assertThat(transitions).containsExactly(
                BATTERY_DEVICE.toHexString() + ":false");
    }

    @Test
    @DisplayName("DP-4 boundary: the battery window rides the persisted instant "
            + "exactly — 24 h-old evidence waits, then fires when the window lapses")
    void seededBattery_windowRidesPersistedInstant() {
        createTracker(Map.of(BATTERY_DEVICE.value(),
                seed(true, clock.instant().minus(Duration.ofHours(24)))));

        tracker.evaluateTimeouts();
        assertThat(tracker.isAvailable(BATTERY_DEVICE))
                .as("24 h of persisted silence is inside the 25 h window")
                .isTrue();
        assertThat(transitions).isEmpty();

        clock.advance(Duration.ofHours(2));
        tracker.evaluateTimeouts();

        assertThat(tracker.isAvailable(BATTERY_DEVICE)).isFalse();
        assertThat(transitions).containsExactly(
                BATTERY_DEVICE.toHexString() + ":false");
    }

    @Test
    @DisplayName("DP-1/T-4: unknown recency (no persisted lastEvidenceAt) is "
            + "infinitely stale — a mains seed is a candidate and a battery seed "
            + "times out, both at the first evaluation")
    void unknownRecency_isInfinitelyStale() {
        createTracker(Map.of(
                MAINS_DEVICE.value(), seed(true, null),
                BATTERY_DEVICE.value(), seed(true, null)));

        List<IEEEAddress> candidates = tracker.evaluateTimeouts();

        assertThat(candidates).containsExactly(MAINS_DEVICE);
        assertThat(tracker.isAvailable(BATTERY_DEVICE))
                .as("unknown recency must never read as fresh")
                .isFalse();
        assertThat(tracker.lastReason(BATTERY_DEVICE))
                .isEqualTo(AvailabilityReason.SILENCE_TIMEOUT);
    }

    @Test
    @DisplayName("T-3: seeding publishes ZERO transitions and logs nothing — every "
            + "seed shape (available, unavailable, unknown; with and without recency)")
    void seeding_publishesNothing_allShapes() {
        createTracker(Map.of(
                BATTERY_DEVICE.value(), seed(true, clock.instant()),
                MAINS_DEVICE.value(), seed(false, null),
                UNKNOWN_DEVICE.value(), seed(null, null),
                DC_DEVICE.value(), seed(null, clock.instant())));

        assertThat(transitions)
                .as("construction/seeding is silent — the anti-churn pin")
                .isEmpty();
        assertThat(tracker.isAvailable(BATTERY_DEVICE)).isTrue();
        assertThat(tracker.isAvailable(MAINS_DEVICE)).isFalse();
        assertThat(tracker.isAvailable(UNKNOWN_DEVICE)).isFalse();
    }

    @Test
    @DisplayName("DP-1: a seeded UNKNOWN-availability device edges online on its "
            + "first evidence — FIRST_CONTACT, exactly as an untracked device would")
    void seededUnknownAvailability_firstEvidenceEdgesOnline() {
        createTracker(Map.of(UNKNOWN_DEVICE.value(), seed(null, null)));

        tracker.recordFrame(UNKNOWN_DEVICE, clock.instant(), ANY_LINK);

        assertThat(tracker.isAvailable(UNKNOWN_DEVICE)).isTrue();
        assertThat(tracker.lastReason(UNKNOWN_DEVICE))
                .isEqualTo(AvailabilityReason.FIRST_CONTACT);
        assertThat(transitions).containsExactly(
                UNKNOWN_DEVICE.toHexString() + ":true");
    }

    @Test
    @DisplayName("DP-1: a seeded UNKNOWN-availability battery device that stays "
            + "silent reaches an honest timeout verdict — UNKNOWN is iterated too")
    void seededUnknownAvailability_stale_timesOutHonestly() {
        createTracker(Map.of(BATTERY_DEVICE.value(), seed(null, null)));

        tracker.evaluateTimeouts();

        assertThat(tracker.isAvailable(BATTERY_DEVICE)).isFalse();
        assertThat(tracker.lastReason(BATTERY_DEVICE))
                .isEqualTo(AvailabilityReason.SILENCE_TIMEOUT);
        assertThat(transitions).containsExactly(
                BATTERY_DEVICE.toHexString() + ":false");
    }

    @Test
    @DisplayName("T-1 boundary: a seeded mains device with FRESH persisted evidence "
            + "is NOT a candidate at the first evaluation")
    void seededFreshMains_notACandidate() {
        createTracker(Map.of(MAINS_DEVICE.value(),
                seed(true, clock.instant().minus(Duration.ofMinutes(1)))));

        assertThat(tracker.evaluateTimeouts()).isEmpty();
        assertThat(transitions).isEmpty();
    }

    @Test
    @DisplayName("boundary: a seeded UNAVAILABLE device is never pinged and never "
            + "re-verdicted — recovery is evidence-driven only")
    void seededUnavailable_neverPinged_neverReverdicted() {
        createTracker(Map.of(MAINS_DEVICE.value(),
                seed(false, clock.instant().minus(Duration.ofDays(3)))));

        assertThat(tracker.evaluateTimeouts()).isEmpty();
        assertThat(transitions).isEmpty();
        assertThat(tracker.isAvailable(MAINS_DEVICE)).isFalse();
    }

    @Test
    @DisplayName("DP-1: isEvidencedAvailable distinguishes seeded-stale from "
            + "this-process evidence — seeded false, frame true, ping-success true")
    void evidencedAvailable_distinguishesSeededFromLive() {
        createTracker(Map.of(
                BATTERY_DEVICE.value(), seed(true, clock.instant()),
                MAINS_DEVICE.value(), seed(false, null)));

        assertThat(tracker.isAvailable(BATTERY_DEVICE)).isTrue();
        assertThat(tracker.isEvidencedAvailable(BATTERY_DEVICE))
                .as("a persisted value is not this-process evidence")
                .isFalse();

        tracker.recordFrame(BATTERY_DEVICE, clock.instant(), ANY_LINK);
        assertThat(tracker.isEvidencedAvailable(BATTERY_DEVICE))
                .as("a frame is evidence even without a transition")
                .isTrue();

        assertThat(tracker.isEvidencedAvailable(MAINS_DEVICE)).isFalse();
        tracker.recordCommandResult(MAINS_DEVICE, true, clock.instant());
        assertThat(tracker.isEvidencedAvailable(MAINS_DEVICE))
                .as("a ping reply is evidence")
                .isTrue();
    }

    // ── LINK-READ: the last link reading is kept per device ─────────────────
    // The STATE only (AUDIT CORRECTION 1): the tracker's log output stays
    // exactly what DP-8 froze — the reading reaches the journal on the
    // adapter's sibling line, pinned in ZigbeeAvailabilityWiringTest.

    @Test
    @DisplayName("LINK-READ T1: a frame's reading survives the silence timeout — the "
            + "device goes unavailable and still answers with its last frame's "
            + "reading and that frame's instant")
    void lastLink_survivesTheSilenceTimeout() {
        createTracker(Map.of());
        Instant frameAt = clock.instant();
        tracker.recordFrame(BATTERY_DEVICE, frameAt,
                Optional.of(new LinkReading(200, -45)));

        clock.advance(Duration.ofHours(26));
        tracker.evaluateTimeouts();

        assertThat(tracker.isAvailable(BATTERY_DEVICE)).isFalse();
        assertThat(tracker.lastReason(BATTERY_DEVICE))
                .isEqualTo(AvailabilityReason.SILENCE_TIMEOUT);
        assertThat(tracker.lastLink(BATTERY_DEVICE))
                .as("the silence carries the last reading — it is never cleared "
                        + "by a transition")
                .contains(new LinkReading(200, -45));
        assertThat(tracker.lastLinkAt(BATTERY_DEVICE))
                .as("the reading's instant is the FRAME's, not the timeout's")
                .contains(frameAt);
    }

    @Test
    @DisplayName("LINK-READ T2: two frames — the second reading wins and the instant "
            + "is the second frame's")
    void lastLink_secondReadingWins() {
        createTracker(Map.of());
        tracker.recordFrame(BATTERY_DEVICE, clock.instant(),
                Optional.of(new LinkReading(200, -45)));

        clock.advance(Duration.ofMinutes(3));
        Instant secondAt = clock.instant();
        tracker.recordFrame(BATTERY_DEVICE, secondAt,
                Optional.of(new LinkReading(120, -78)));

        assertThat(tracker.lastLink(BATTERY_DEVICE))
                .contains(new LinkReading(120, -78));
        assertThat(tracker.lastLinkAt(BATTERY_DEVICE)).contains(secondAt);
    }

    @Test
    @DisplayName("LINK-READ T2-b: an EMPTY reading is still liveness — last-seen moves "
            + "(the silence window restarts) and the prior reading and its instant "
            + "are kept")
    void emptyReading_isStillLiveness_andKeepsThePriorReading() {
        createTracker(Map.of());
        Instant firstAt = clock.instant();
        tracker.recordFrame(BATTERY_DEVICE, firstAt,
                Optional.of(new LinkReading(200, -45)));

        clock.advance(Duration.ofHours(24));
        tracker.recordFrame(BATTERY_DEVICE, clock.instant(), Optional.empty());
        clock.advance(Duration.ofHours(24));
        tracker.evaluateTimeouts();

        assertThat(tracker.isAvailable(BATTERY_DEVICE))
                .as("48 h after the first frame, 24 h after the second: the "
                        + "reading-less frame restarted the 25 h window")
                .isTrue();
        assertThat(tracker.lastLink(BATTERY_DEVICE))
                .as("an empty reading never overwrites a kept one")
                .contains(new LinkReading(200, -45));
        assertThat(tracker.lastLinkAt(BATTERY_DEVICE)).contains(firstAt);
    }

    @Test
    @DisplayName("LINK-READ T3: a seeded (persisted) device with no this-process frame "
            + "times out with NO reading — none is invented for it (DP-1)")
    void seededDevice_timesOutWithNoReading() {
        createTracker(Map.of(BATTERY_DEVICE.value(),
                seed(true, clock.instant().minus(Duration.ofHours(26)))));

        tracker.evaluateTimeouts();

        assertThat(tracker.isAvailable(BATTERY_DEVICE)).isFalse();
        assertThat(tracker.lastReason(BATTERY_DEVICE))
                .isEqualTo(AvailabilityReason.SILENCE_TIMEOUT);
        assertThat(tracker.lastLink(BATTERY_DEVICE)).isEmpty();
        assertThat(tracker.lastLinkAt(BATTERY_DEVICE)).isEmpty();
        assertThat(tracker.lastLink(MAINS_DEVICE))
                .as("a device the tracker has never heard of has no reading either")
                .isEmpty();
    }
}
