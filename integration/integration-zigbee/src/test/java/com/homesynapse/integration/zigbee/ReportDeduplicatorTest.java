/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import com.homesynapse.test.TestClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ReportDeduplicator} tests — the MEASURED dedup contract: every SNZB
 * event arrives ×2 with consecutive TSNs and identical payloads 8–21 ms apart;
 * TSN is 8-bit wrapping AND resets on power-cycle, so equality is on
 * (TSN-adjacency + payload) within the (device, endpoint, cluster) scope —
 * never TSN alone, and never untimed (F-4: a scope entry older than the
 * {@link ReportDeduplicator#DEDUP_WINDOW_MS} twin window never dedups). The
 * scope clears on device-announce/rejoin.
 */
class ReportDeduplicatorTest {

    private static final IEEEAddress DEVICE = new IEEEAddress(0x00124B0012345678L);
    private static final IEEEAddress OTHER = new IEEEAddress(0x00124B00AAAAAAAAL);
    private static final byte[] OCCUPIED = {0x18, 0x2A, 0x0A, 0x00, 0x00, 0x18, 0x01};
    private static final byte[] CLEAR = {0x18, 0x2B, 0x0A, 0x00, 0x00, 0x18, 0x00};

    private final TestClock clock = TestClock.createDefault();
    private final ReportDeduplicator dedup = new ReportDeduplicator(clock);

    @Test
    @DisplayName("the measured ×2 pattern: consecutive TSN + identical payload drops the twin")
    void measuredDoublePatternDrops() {
        assertThat(dedup.isDuplicate(DEVICE, 1, 0x0406, 42, OCCUPIED)).isFalse();
        assertThat(dedup.isDuplicate(DEVICE, 1, 0x0406, 43, OCCUPIED)).isTrue();
    }

    @Test
    @DisplayName("a consecutive TSN with a DIFFERENT payload is a genuine report")
    void consecutiveTsnDifferentPayloadKept() {
        assertThat(dedup.isDuplicate(DEVICE, 1, 0x0406, 46, OCCUPIED)).isFalse();
        assertThat(dedup.isDuplicate(DEVICE, 1, 0x0406, 47, CLEAR)).isFalse();
    }

    @Test
    @DisplayName("an identical payload with a NON-consecutive TSN is a genuine report "
            + "(distinct edges legitimately repeat values)")
    void gapTsnSamePayloadKept() {
        assertThat(dedup.isDuplicate(DEVICE, 1, 0x0406, 42, OCCUPIED)).isFalse();
        assertThat(dedup.isDuplicate(DEVICE, 1, 0x0406, 47, OCCUPIED)).isFalse();
    }

    @Test
    @DisplayName("the 8-bit TSN wrap: 255 → 0 is consecutive")
    void tsnWrapIsConsecutive() {
        assertThat(dedup.isDuplicate(DEVICE, 1, 0x0406, 255, OCCUPIED)).isFalse();
        assertThat(dedup.isDuplicate(DEVICE, 1, 0x0406, 0, OCCUPIED)).isTrue();
    }

    @Test
    @DisplayName("an identical retransmit (same TSN, same payload) also drops")
    void identicalRetransmitDrops() {
        assertThat(dedup.isDuplicate(DEVICE, 1, 0x0406, 42, OCCUPIED)).isFalse();
        assertThat(dedup.isDuplicate(DEVICE, 1, 0x0406, 42, OCCUPIED)).isTrue();
    }

    @Test
    @DisplayName("the scope is per (device, endpoint, cluster)")
    void scopeIsPerDeviceEndpointCluster() {
        assertThat(dedup.isDuplicate(DEVICE, 1, 0x0406, 42, OCCUPIED)).isFalse();
        assertThat(dedup.isDuplicate(OTHER, 1, 0x0406, 43, OCCUPIED)).isFalse();
        assertThat(dedup.isDuplicate(DEVICE, 2, 0x0406, 43, OCCUPIED)).isFalse();
        assertThat(dedup.isDuplicate(DEVICE, 1, 0x0006, 43, OCCUPIED)).isFalse();
    }

    @Test
    @DisplayName("F-4: the measured sub-second twin still drops inside the window")
    void subSecondTwinStillDrops() {
        assertThat(dedup.isDuplicate(DEVICE, 1, 0x0406, 42, OCCUPIED)).isFalse();

        // The measured twin band's slow edge (8–21 ms).
        clock.advance(Duration.ofMillis(21));

        assertThat(dedup.isDuplicate(DEVICE, 1, 0x0406, 43, OCCUPIED)).isTrue();
    }

    @Test
    @DisplayName("F-4: a same-payload consecutive-TSN pair minutes apart is a genuine "
            + "periodic report (the false-drop regression)")
    void periodicRepeatOutsideWindowKept() {
        assertThat(dedup.isDuplicate(DEVICE, 1, 0x0406, 42, OCCUPIED)).isFalse();

        // The periodic-reporting scale: an unchanged value re-reported on the
        // device's schedule can land on the successor TSN.
        clock.advance(Duration.ofMinutes(5));

        assertThat(dedup.isDuplicate(DEVICE, 1, 0x0406, 43, OCCUPIED))
                .as("outside the twin window the repeat is a genuine observation")
                .isFalse();
    }

    @Test
    @DisplayName("F-4: just past the window the pair no longer dedups")
    void repeatJustPastWindowKept() {
        assertThat(dedup.isDuplicate(DEVICE, 1, 0x0406, 42, OCCUPIED)).isFalse();

        clock.advance(Duration.ofMillis(ReportDeduplicator.DEDUP_WINDOW_MS + 1));

        assertThat(dedup.isDuplicate(DEVICE, 1, 0x0406, 43, OCCUPIED)).isFalse();
    }

    @Test
    @DisplayName("device-announce/rejoin clears the scope (TSN resets on power-cycle)")
    void announceClearsScope() {
        assertThat(dedup.isDuplicate(DEVICE, 1, 0x0406, 42, OCCUPIED)).isFalse();

        dedup.clearDevice(DEVICE);

        assertThat(dedup.isDuplicate(DEVICE, 1, 0x0406, 43, OCCUPIED))
                .as("post-rejoin the prior TSN state is stale truth")
                .isFalse();
    }
}
