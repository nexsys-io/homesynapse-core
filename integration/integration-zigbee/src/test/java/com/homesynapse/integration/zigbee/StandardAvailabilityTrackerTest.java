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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    private void createTracker(Map<Long, Boolean> persisted) {
        tracker = new StandardAvailabilityTracker(clock,
                device -> powerSources.getOrDefault(device.value(), 0),
                persisted,
                (device, available) ->
                        transitions.add(device.toHexString() + ":" + available));
    }

    @Test
    @DisplayName("a new device transitions to available on FIRST_CONTACT")
    void firstContactTransitions() {
        createTracker(Map.of());

        tracker.recordFrame(BATTERY_DEVICE, clock.instant());

        assertThat(tracker.isAvailable(BATTERY_DEVICE)).isTrue();
        assertThat(tracker.lastReason(BATTERY_DEVICE))
                .isEqualTo(AvailabilityReason.FIRST_CONTACT);
        assertThat(transitions).hasSize(1);
    }

    @Test
    @DisplayName("restart-init: persisted AVAILABLE state carries forward with NO transition")
    void restartInitCarriesForwardSilently() {
        createTracker(Map.of(BATTERY_DEVICE.value(), true));

        assertThat(tracker.isAvailable(BATTERY_DEVICE)).isTrue();
        assertThat(transitions)
                .as("a planned restart must not produce a false "
                        + "unavailable→available cascade")
                .isEmpty();

        tracker.recordFrame(BATTERY_DEVICE, clock.instant());

        assertThat(transitions)
                .as("the first post-restart frame confirms, never transitions")
                .isEmpty();
    }

    @Test
    @DisplayName("restart-init: persisted UNAVAILABLE stays unavailable until a frame arrives")
    void restartInitUnavailableUntilFrame() {
        createTracker(Map.of(BATTERY_DEVICE.value(), false));

        assertThat(tracker.isAvailable(BATTERY_DEVICE)).isFalse();

        tracker.recordFrame(BATTERY_DEVICE, clock.instant());

        assertThat(tracker.isAvailable(BATTERY_DEVICE)).isTrue();
        assertThat(transitions).hasSize(1);
        assertThat(tracker.lastReason(BATTERY_DEVICE))
                .isEqualTo(AvailabilityReason.FRAME_RECEIVED);
    }

    @Test
    @DisplayName("battery devices go unavailable after 25 h of silence")
    void batterySilenceTimeout() {
        createTracker(Map.of());
        tracker.recordFrame(BATTERY_DEVICE, clock.instant());
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
        tracker.recordFrame(MAINS_DEVICE, clock.instant());
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
        tracker.recordFrame(THREE_PHASE_DEVICE, clock.instant());
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
        tracker.recordFrame(UNKNOWN_DEVICE, clock.instant());
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
        tracker.recordFrame(DC_DEVICE, clock.instant());
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
        tracker.recordFrame(MAINS_DEVICE, clock.instant());
        transitions.clear();

        tracker.recordCommandResult(MAINS_DEVICE, false, clock.instant());

        assertThat(tracker.isAvailable(MAINS_DEVICE)).isFalse();
        assertThat(tracker.lastReason(MAINS_DEVICE))
                .isEqualTo(AvailabilityReason.PING_TIMEOUT);
    }

    @Test
    @DisplayName("a successful command confirms reachability")
    void successfulCommandConfirms() {
        createTracker(Map.of(MAINS_DEVICE.value(), false));

        tracker.recordCommandResult(MAINS_DEVICE, true, clock.instant());

        assertThat(tracker.isAvailable(MAINS_DEVICE)).isTrue();
        assertThat(tracker.lastReason(MAINS_DEVICE))
                .isEqualTo(AvailabilityReason.PING_SUCCESS);
    }
}
