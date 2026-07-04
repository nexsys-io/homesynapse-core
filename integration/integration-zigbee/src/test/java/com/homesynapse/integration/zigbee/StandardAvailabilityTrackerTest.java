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
 * transitions.
 */
class StandardAvailabilityTrackerTest {

    private static final IEEEAddress BATTERY_DEVICE =
            new IEEEAddress(0x00124B0012345678L);
    private static final IEEEAddress MAINS_DEVICE =
            new IEEEAddress(0x0017880109AB12CDL);

    private TestClock clock;
    private Map<Long, Integer> powerSources;
    private List<String> transitions;
    private StandardAvailabilityTracker tracker;

    @BeforeEach
    void setUp() {
        clock = TestClock.createDefault();
        powerSources = new HashMap<>();
        powerSources.put(BATTERY_DEVICE.value(), 3); // ZCL battery
        powerSources.put(MAINS_DEVICE.value(), 1);   // ZCL mains single phase
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
        tracker.evaluateTimeouts();
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
