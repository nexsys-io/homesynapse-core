/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.homesynapse.test.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link StandardAvailabilityTracker} tests (Doc 08 §3.11 availability +
 * §8.1 M-1 restart initialization): power-source-aware silence timeouts,
 * transition listener firing, and the planned-restart rule — initialization
 * from persisted pre-restart state emits NO false unavailable→available
 * transitions. The M9.4b §6.10 N-5 posture pins: battery UNLESS the ZCL Basic
 * PowerSource value is a mains class (0x01/0x02) — UNKNOWN (0x00) and exotic
 * values get the 25 h battery-conservative window, never the 60-s active-ping
 * regime (IR-121: 60 s since J1, was 10 min). J1 adds the per-device silence
 * limit (the declared expected report interval through the injected lookup;
 * empty → 25 h) and the listener's snapshot of reason, last-seen and the last
 * link reading (LINK-READ-2). AVAIL-SHAPE (IR-137/IR-138) adds the mains
 * contract lookup — the configured reporting maximum PLUS the 60-s floor as a
 * mains device's silence-before-probe, the floor alone where there is none —
 * and the K = 2 probe backstop ({@code PROBE_MISSES_TO_DARK}).
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
    /** Every listener call, whole (LINK-READ-2: the snapshot the publish site reads). */
    private List<Observed> observed;
    private StandardAvailabilityTracker tracker;

    /** One transition as the listener received it. */
    private record Observed(IEEEAddress device, Instant instant, boolean available,
            AvailabilityReason reason, Instant lastSeen, LinkReading lastLink,
            Instant lastLinkAt) { }

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
        observed = new ArrayList<>();
    }

    /** The tracker with NO declared intervals: every non-mains device takes the 25 h window. */
    private void createTracker(
            Map<Long, StandardAvailabilityTracker.Seed> persisted) {
        createTracker(persisted, device -> Optional.empty());
    }

    private void createTracker(
            Map<Long, StandardAvailabilityTracker.Seed> persisted,
            Function<IEEEAddress, Optional<Duration>> expectedSilenceLookup) {
        createTracker(persisted, expectedSilenceLookup, device -> Optional.empty());
    }

    /**
     * AVAIL-SHAPE (IR-138): the tracker with all three lookups — the mains
     * contract lookup yields the device's configured reporting maximum, empty
     * where the Core configured none (the 60-s floor alone applies).
     */
    private void createTracker(
            Map<Long, StandardAvailabilityTracker.Seed> persisted,
            Function<IEEEAddress, Optional<Duration>> expectedSilenceLookup,
            Function<IEEEAddress, Optional<Duration>> mainsContractLookup) {
        tracker = new StandardAvailabilityTracker(clock,
                device -> powerSources.getOrDefault(device.value(), 0),
                expectedSilenceLookup,
                mainsContractLookup,
                persisted,
                (device, instant, available, reason, lastSeen, lastLink, lastLinkAt) -> {
                    transitions.add(device.toHexString() + ":" + available);
                    observed.add(new Observed(device, instant, available, reason,
                            lastSeen, lastLink, lastLinkAt));
                });
    }

    /** A seed entry (WU-AVAIL-SEED DP-1): both components nullable. */
    private static StandardAvailabilityTracker.Seed seed(Boolean available,
            Instant lastEvidenceAt) {
        return new StandardAvailabilityTracker.Seed(available, lastEvidenceAt);
    }

    /** A mains-contract lookup answering {@code seconds} for every device. */
    private static Function<IEEEAddress, Optional<Duration>> contractOf(long seconds) {
        return device -> Optional.of(Duration.ofSeconds(seconds));
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
    @DisplayName("mains devices become ping candidates after 60 s of silence (IR-121; this "
            + "test advances 11 min) — not yet unavailable")
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
    @DisplayName("IR-121 T1: a mains device silent 61 s is a ping candidate; silent 59 s "
            + "it is not (MAINS_PING_SILENCE = 60 s, the compare strict)")
    void mainsSilence_61sIsACandidate_59sIsNot() {
        createTracker(Map.of());
        tracker.recordFrame(MAINS_DEVICE, clock.instant(), ANY_LINK);
        transitions.clear();

        clock.advance(Duration.ofSeconds(59));
        assertThat(tracker.evaluateTimeouts())
                .as("59 s of silence is inside the 60-s window")
                .isEmpty();

        clock.advance(Duration.ofSeconds(2));
        assertThat(tracker.evaluateTimeouts())
                .as("61 s of silence makes a contract-less mains device a ping candidate — "
                        + "named dark inside D-v94-25's 90 s once two 5-s probes lapse "
                        + "(AVAIL-SHAPE: 70.05 s)")
                .containsExactly(MAINS_DEVICE);
        assertThat(tracker.isAvailable(MAINS_DEVICE))
                .as("candidacy is not a verdict — the ping decides")
                .isTrue();
        assertThat(transitions).isEmpty();
    }

    // ── IR-121: the per-device silence limit (the declared expected interval) ──

    @Test
    @DisplayName("IR-121 T2: a non-mains device whose lookup declares 7200 s transitions "
            + "UNAVAILABLE/SILENCE_TIMEOUT at 7201 s and not at 7199 s; a device the "
            + "lookup leaves empty keeps the 25 h window; UNAVAILABLE is never re-verdicted")
    void declaredInterval_setsTheSilenceLimit() {
        Map<Long, Optional<Duration>> limits = new HashMap<>();
        limits.put(BATTERY_DEVICE.value(), Optional.of(Duration.ofSeconds(7200)));
        createTracker(Map.of(),
                device -> limits.getOrDefault(device.value(), Optional.empty()));
        tracker.recordFrame(BATTERY_DEVICE, clock.instant(), ANY_LINK);
        tracker.recordFrame(DC_DEVICE, clock.instant(), ANY_LINK);
        transitions.clear();

        clock.advance(Duration.ofSeconds(7199));
        assertThat(tracker.evaluateTimeouts())
                .as("N-5: non-mains devices never enter the ping regime")
                .isEmpty();
        assertThat(tracker.isAvailable(BATTERY_DEVICE))
                .as("7199 s is inside the declared 7200-s limit")
                .isTrue();

        clock.advance(Duration.ofSeconds(2));
        tracker.evaluateTimeouts();

        assertThat(tracker.isAvailable(BATTERY_DEVICE))
                .as("7201 s of silence exceeds the declared limit")
                .isFalse();
        assertThat(tracker.lastReason(BATTERY_DEVICE))
                .isEqualTo(AvailabilityReason.SILENCE_TIMEOUT);
        assertThat(tracker.isAvailable(DC_DEVICE))
                .as("an empty lookup keeps the 25 h window")
                .isTrue();
        assertThat(transitions).containsExactly(
                BATTERY_DEVICE.toHexString() + ":false");

        clock.advance(Duration.ofHours(1));
        tracker.evaluateTimeouts();
        assertThat(transitions)
                .as("an UNAVAILABLE device is never re-verdicted — recovery is "
                        + "evidence-driven")
                .hasSize(1);
    }

    @Test
    @DisplayName("IR-121 T2b: a lookup that THROWS takes the 25 h arm for that cycle and "
            + "logs zigbee.availability_limit_lookup_failed once per device per cycle — "
            + "never a stuck cycle")
    void throwingLookup_takesThe25hArm_andWarnsOncePerDevicePerCycle() {
        Logger logger = (Logger) LoggerFactory.getLogger(StandardAvailabilityTracker.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            createTracker(Map.of(), device -> {
                throw new IllegalStateException("registry unavailable");
            });
            tracker.recordFrame(BATTERY_DEVICE, clock.instant(), ANY_LINK);
            transitions.clear();

            clock.advance(Duration.ofHours(2));
            assertThat(tracker.evaluateTimeouts()).isEmpty();
            assertThat(tracker.isAvailable(BATTERY_DEVICE))
                    .as("the failed lookup falls to the 25 h window — not a verdict")
                    .isTrue();

            clock.advance(Duration.ofHours(24));
            tracker.evaluateTimeouts();
            assertThat(tracker.isAvailable(BATTERY_DEVICE))
                    .as("26 h of silence under the fallback window")
                    .isFalse();

            List<String> warnings = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(m -> m.startsWith("zigbee.availability_limit_lookup_failed:"))
                    .toList();
            assertThat(warnings)
                    .as("one WARN per device per evaluation cycle — two cycles")
                    .hasSize(2);
            assertThat(warnings.get(0))
                    .contains("device=" + BATTERY_DEVICE)
                    .contains("registry unavailable");
        } finally {
            logger.detachAppender(appender);
        }
    }

    // ── LINK-READ-2: the listener's snapshot (reason · last-seen · last reading) ──

    @Test
    @DisplayName("LINK-READ-2 T3: on a SILENCE_TIMEOUT transition the listener receives the "
            + "device's LAST reading and last-seen instant (an earlier frame's) — never "
            + "null, never the timeout's own instant")
    void silenceTimeout_listenerReceivesTheLastReadingAndLastSeen() {
        createTracker(Map.of());
        Instant frameAt = clock.instant();
        tracker.recordFrame(BATTERY_DEVICE, frameAt,
                Optional.of(new LinkReading(200, -45)));

        clock.advance(Duration.ofHours(26));
        tracker.evaluateTimeouts();

        assertThat(observed).hasSize(2);
        Observed online = observed.get(0);
        assertThat(online.available()).isTrue();
        assertThat(online.reason()).isEqualTo(AvailabilityReason.FIRST_CONTACT);
        assertThat(online.instant()).isEqualTo(frameAt);
        assertThat(online.lastSeen())
                .as("the online edge's last-seen is the frame that produced it")
                .isEqualTo(frameAt);
        assertThat(online.lastLink()).isEqualTo(new LinkReading(200, -45));
        assertThat(online.lastLinkAt()).isEqualTo(frameAt);

        Observed timeout = observed.get(1);
        assertThat(timeout.available()).isFalse();
        assertThat(timeout.reason()).isEqualTo(AvailabilityReason.SILENCE_TIMEOUT);
        assertThat(timeout.instant())
                .as("the transition's own instant is the verdict's")
                .isEqualTo(clock.instant());
        assertThat(timeout.lastSeen())
                .as("last-seen is the last evidence instant — the frame, not the timeout")
                .isEqualTo(frameAt);
        assertThat(timeout.lastLink())
                .as("the LAST reading survives the transition")
                .isEqualTo(new LinkReading(200, -45));
        assertThat(timeout.lastLinkAt()).isEqualTo(frameAt);
    }

    @Test
    @DisplayName("LINK-READ-2 T3b: a seeded device that never spoke this process times out "
            + "with the SEED's instant as last-seen and NO reading (null, null) — nothing "
            + "is invented")
    void seededTimeout_listenerReceivesTheSeedInstantAndNoReading() {
        Instant seedAt = clock.instant().minus(Duration.ofHours(26));
        createTracker(Map.of(BATTERY_DEVICE.value(), seed(true, seedAt)));

        tracker.evaluateTimeouts();

        assertThat(observed).hasSize(1);
        Observed timeout = observed.get(0);
        assertThat(timeout.available()).isFalse();
        assertThat(timeout.reason()).isEqualTo(AvailabilityReason.SILENCE_TIMEOUT);
        assertThat(timeout.lastSeen()).isEqualTo(seedAt);
        assertThat(timeout.lastLink()).isNull();
        assertThat(timeout.lastLinkAt()).isNull();
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
    @DisplayName("AVAIL-SHAPE U-3: a failed command result after repeated failures marks "
            + "unavailable via PING_TIMEOUT — the FIRST miss leaves the device AVAILABLE, its "
            + "reason unchanged and the listener silent; the SECOND (PROBE_MISSES_TO_DARK = 2) "
            + "names it dark with ONE transition")
    void failedPingMarksUnavailable() {
        createTracker(Map.of());
        tracker.recordFrame(MAINS_DEVICE, clock.instant(), ANY_LINK);
        transitions.clear();

        tracker.recordCommandResult(MAINS_DEVICE, false, clock.instant());

        assertThat(StandardAvailabilityTracker.PROBE_MISSES_TO_DARK).isEqualTo(2);
        assertThat(tracker.isAvailable(MAINS_DEVICE))
                .as("one miss is a lost frame or a lost reply, never a verdict (K = 2)")
                .isTrue();
        assertThat(tracker.lastReason(MAINS_DEVICE))
                .isEqualTo(AvailabilityReason.FIRST_CONTACT);
        assertThat(transitions).isEmpty();

        tracker.recordCommandResult(MAINS_DEVICE, false, clock.instant());

        assertThat(tracker.isAvailable(MAINS_DEVICE)).isFalse();
        assertThat(tracker.lastReason(MAINS_DEVICE))
                .isEqualTo(AvailabilityReason.PING_TIMEOUT);
        assertThat(transitions).containsExactly(MAINS_DEVICE.toHexString() + ":false");
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

    // ── AVAIL-SHAPE (IR-137/IR-138): the contract-derived mains limit + K = 2 ──
    // A mains device's silence-before-probe is the reporting CONTRACT the Core
    // configured on it (its smallest effective maximum, through the injected
    // lookup) PLUS the 60-s floor — the floor alone where there is none. Two
    // consecutive probe misses name dark; a frame or a reply resets the count.

    @Test
    @DisplayName("AVAIL-SHAPE U-1 (the 70-s fixture): a mains device whose contract is 600 s "
            + "is NOT a ping candidate at 70 s, nor at 600 s (the report due); at 661 s "
            + "(contract + the 60-s floor, strict) it is — and still AVAILABLE")
    void mainsContract_600s_notACandidateAt70s_candidateAt661s() {
        createTracker(Map.of(), device -> Optional.empty(), contractOf(600));
        tracker.recordFrame(MAINS_DEVICE, clock.instant(), ANY_LINK);
        transitions.clear();

        clock.advance(Duration.ofSeconds(70));
        assertThat(tracker.evaluateTimeouts())
                .as("70 s of silence under a 600-s contract is a plug keeping its "
                        + "contract — SOAK-NIGHT-1's five flaps")
                .isEmpty();
        assertThat(tracker.isAvailable(MAINS_DEVICE)).isTrue();

        clock.advance(Duration.ofSeconds(530));
        assertThat(tracker.evaluateTimeouts())
                .as("600 s: the report is due, the floor is not yet spent")
                .isEmpty();

        clock.advance(Duration.ofSeconds(61));
        assertThat(tracker.evaluateTimeouts())
                .as("661 s > 600 + 60: the probe is due")
                .containsExactly(MAINS_DEVICE);
        assertThat(tracker.isAvailable(MAINS_DEVICE))
                .as("candidacy is not a verdict — the probes decide")
                .isTrue();
        assertThat(transitions).isEmpty();
    }

    @Test
    @DisplayName("AVAIL-SHAPE U-2: the floor is ADDED to the contract, never maxed — a 30-s "
            + "contract makes the limit 90 s: not a candidate at 90 s, a candidate at 91 s; "
            + "a device with an EMPTY contract keeps the 60-s floor alone")
    void mainsContract_floorIsAdded_30sContractNamesAt91s() {
        Map<Long, Optional<Duration>> contracts = new HashMap<>();
        contracts.put(MAINS_DEVICE.value(), Optional.of(Duration.ofSeconds(30)));
        createTracker(Map.of(), device -> Optional.empty(),
                device -> contracts.getOrDefault(device.value(), Optional.empty()));
        tracker.recordFrame(MAINS_DEVICE, clock.instant(), ANY_LINK);
        tracker.recordFrame(THREE_PHASE_DEVICE, clock.instant(), ANY_LINK);
        transitions.clear();

        clock.advance(Duration.ofSeconds(90));
        assertThat(tracker.evaluateTimeouts())
                .as("90 s = 30 + 60 exactly — the strict compare holds the boundary; the "
                        + "contract-less device is past its 60-s floor")
                .containsExactly(THREE_PHASE_DEVICE);

        clock.advance(Duration.ofSeconds(1));
        assertThat(tracker.evaluateTimeouts())
                .as("91 s > 30 + 60: both are due")
                .containsExactlyInAnyOrder(MAINS_DEVICE, THREE_PHASE_DEVICE);
        assertThat(transitions).isEmpty();
    }

    @Test
    @DisplayName("AVAIL-SHAPE U-2b: a mains-contract lookup that THROWS falls to the 60-s floor "
            + "for that cycle and logs zigbee.availability_limit_lookup_failed once per device "
            + "per cycle — never a stuck cycle, never a verdict from a failed read")
    void throwingMainsContractLookup_fallsToTheFloor_andWarns() {
        Logger logger = (Logger) LoggerFactory.getLogger(StandardAvailabilityTracker.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            createTracker(Map.of(), device -> Optional.empty(), device -> {
                throw new IllegalStateException("cache unavailable");
            });
            tracker.recordFrame(MAINS_DEVICE, clock.instant(), ANY_LINK);
            transitions.clear();

            clock.advance(Duration.ofSeconds(59));
            assertThat(tracker.evaluateTimeouts()).isEmpty();
            clock.advance(Duration.ofSeconds(2));
            assertThat(tracker.evaluateTimeouts())
                    .as("the failed lookup reads as no contract: the floor applies")
                    .containsExactly(MAINS_DEVICE);

            List<String> warnings = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(m -> m.startsWith("zigbee.availability_limit_lookup_failed:"))
                    .toList();
            assertThat(warnings)
                    .as("one WARN per device per evaluation cycle — two cycles")
                    .hasSize(2);
            assertThat(warnings.get(0))
                    .contains("device=" + MAINS_DEVICE)
                    .contains("the 60-s floor")
                    .contains("cache unavailable");
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("AVAIL-SHAPE U-4: a frame between two misses RESETS the count — miss, frame, "
            + "miss leaves the device AVAILABLE; a second consecutive miss then names it dark")
    void probeMiss_thenFrame_thenMiss_staysAvailable() {
        createTracker(Map.of());
        tracker.recordFrame(MAINS_DEVICE, clock.instant(), ANY_LINK);
        transitions.clear();

        tracker.recordCommandResult(MAINS_DEVICE, false, clock.instant());
        clock.advance(Duration.ofSeconds(5));
        tracker.recordFrame(MAINS_DEVICE, clock.instant(), ANY_LINK);
        tracker.recordCommandResult(MAINS_DEVICE, false, clock.instant());

        assertThat(tracker.isAvailable(MAINS_DEVICE))
                .as("the frame reset the count: the second miss is a first miss again")
                .isTrue();
        assertThat(tracker.lastReason(MAINS_DEVICE))
                .isEqualTo(AvailabilityReason.FIRST_CONTACT);
        assertThat(transitions).isEmpty();

        tracker.recordCommandResult(MAINS_DEVICE, false, clock.instant());

        assertThat(tracker.isAvailable(MAINS_DEVICE)).isFalse();
        assertThat(tracker.lastReason(MAINS_DEVICE))
                .isEqualTo(AvailabilityReason.PING_TIMEOUT);
        assertThat(transitions).containsExactly(MAINS_DEVICE.toHexString() + ":false");
    }

    @Test
    @DisplayName("AVAIL-SHAPE U-4b: a miss then a reply — the device stays AVAILABLE and the "
            + "count is back at 0: the next single miss does not transition, the one after "
            + "does (AVAILABLE → AVAILABLE is no edge, so the reason keeps the edging one)")
    void probeMiss_thenSuccess_resetsTheCount() {
        createTracker(Map.of());
        tracker.recordFrame(MAINS_DEVICE, clock.instant(), ANY_LINK);
        transitions.clear();

        tracker.recordCommandResult(MAINS_DEVICE, false, clock.instant());
        tracker.recordCommandResult(MAINS_DEVICE, true, clock.instant());

        assertThat(tracker.isAvailable(MAINS_DEVICE)).isTrue();
        assertThat(tracker.isEvidencedAvailable(MAINS_DEVICE))
                .as("a reply is this-process evidence")
                .isTrue();
        assertThat(tracker.lastReason(MAINS_DEVICE))
                .as("no edge: the reason is the transition's, and none happened (J1's "
                        + "contract — the v2 event mirrors lastReason)")
                .isEqualTo(AvailabilityReason.FIRST_CONTACT);
        assertThat(transitions).isEmpty();

        tracker.recordCommandResult(MAINS_DEVICE, false, clock.instant());
        assertThat(tracker.isAvailable(MAINS_DEVICE))
                .as("the reply zeroed the count: this miss is the first")
                .isTrue();

        tracker.recordCommandResult(MAINS_DEVICE, false, clock.instant());
        assertThat(tracker.isAvailable(MAINS_DEVICE)).isFalse();
        assertThat(tracker.lastReason(MAINS_DEVICE))
                .isEqualTo(AvailabilityReason.PING_TIMEOUT);
        assertThat(transitions).containsExactly(MAINS_DEVICE.toHexString() + ":false");
    }

    @Test
    @DisplayName("AVAIL-SHAPE U-5: a seeded-stale mains entry is still a candidate at the FIRST "
            + "evaluation, and its first miss does not transition — the seed's count starts "
            + "at 0 and the next cycle probes it again")
    void seededStaleMains_firstMissDoesNotTransition() {
        createTracker(Map.of(MAINS_DEVICE.value(),
                seed(true, clock.instant().minus(Duration.ofHours(3)))));

        assertThat(tracker.evaluateTimeouts()).containsExactly(MAINS_DEVICE);
        tracker.recordCommandResult(MAINS_DEVICE, false, clock.instant());

        assertThat(tracker.isAvailable(MAINS_DEVICE)).isTrue();
        assertThat(transitions).isEmpty();
        assertThat(tracker.evaluateTimeouts())
                .as("still past its limit and still AVAILABLE: the next cycle probes again")
                .containsExactly(MAINS_DEVICE);

        tracker.recordCommandResult(MAINS_DEVICE, false, clock.instant());

        assertThat(tracker.isAvailable(MAINS_DEVICE)).isFalse();
        assertThat(tracker.lastReason(MAINS_DEVICE))
                .isEqualTo(AvailabilityReason.PING_TIMEOUT);
        assertThat(transitions).containsExactly(MAINS_DEVICE.toHexString() + ":false");
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
        // IR-121: 30 s of persisted silence — inside the 60-s probe window (the
        // pre-J1 1-min seed would sit ON the boundary of the strict compare).
        createTracker(Map.of(MAINS_DEVICE.value(),
                seed(true, clock.instant().minus(Duration.ofSeconds(30)))));

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
