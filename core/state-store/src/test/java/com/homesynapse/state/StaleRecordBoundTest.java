/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

import static org.assertj.core.api.Assertions.assertThat;

import com.homesynapse.event.AvailabilityContractDeclaredEvent;
import com.homesynapse.event.ProbeAnsweredEvent;
import com.homesynapse.platform.identity.DeviceId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * AVAIL-API-1 V-5 (AMD-103 R-G) — the stale-record bound, computed at read in ONE place: the
 * evidence instant is the newest of the state projection's {@code lastReported}, the record's
 * {@code probeAt}, its newest {@code availabilityChangedAt} and its contract's instant; the
 * bound for an asked class is {@code recordIntervalSeconds + silenceLimitSeconds + missesToDark
 * × 5 s × N + one sweep} (N = the devices whose newest contract names an asked class), for the
 * passive class {@code silenceLimitSeconds + one sweep}; no contract → not computed.
 */
@DisplayName("StaleRecordBound — R-G's bound at read (AVAIL-API-1 V-5)")
final class StaleRecordBoundTest {

    private static final DeviceId DEVICE = DeviceId.parse("01JAAAAAAAAAAAAAAAAAAAAAD1");
    private static final Instant T1 = Instant.parse("2026-10-11T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-10-11T10:30:00Z");
    private static final Instant T3 = Instant.parse("2026-10-11T11:00:00Z");
    private static final Instant T4 = Instant.parse("2026-10-11T09:00:00Z");

    private static final AvailabilityContractDeclaredEvent METERED =
            new AvailabilityContractDeclaredEvent("mains-metered", 600, 660, 2, 3600);
    private static final AvailabilityContractDeclaredEvent PASSIVE =
            new AvailabilityContractDeclaredEvent("passive", 7200, 7200, 2, null);

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    StaleRecordBoundTest() {
    }

    /** probeAt T2, availabilityChangedAt T3, contractAt T4 — T3 the newest of the record's three. */
    private static RecoveryCardRecord record(AvailabilityContractDeclaredEvent contract) {
        return RecoveryCardRecord.empty(DEVICE)
                .withProbe(new ProbeAnsweredEvent(T2, T2.minusSeconds(3600), 55, 0, null, null, null))
                .withContract(contract, T4)
                .withAvailabilityChangedAt(T3);
    }

    @Test
    @DisplayName("the constants: one sweep is 50 ms (PRODUCTION_CYCLE_MILLIS) and the probe "
            + "deadline 5 s (AVAILABILITY_PING_TIMEOUT_MILLIS) — carried here, never a module edge")
    void constants() {
        assertThat(StaleRecordBound.SWEEP).isEqualTo(Duration.ofMillis(50));
        assertThat(StaleRecordBound.PROBE_DEADLINE).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("V-5: no contract record → the bound is not computed: dueBy and overdueSince empty")
    void noContract_notComputed() {
        RecoveryCardRecord noContract = RecoveryCardRecord.empty(DEVICE)
                .withProbe(new ProbeAnsweredEvent(T2, T1, 1, 0, null, null, null));

        assertThat(StaleRecordBound.bound(noContract, 1, StaleRecordBound.SWEEP)).isEmpty();
        assertThat(StaleRecordBound.dueBy(noContract, T1, 1, StaleRecordBound.SWEEP)).isEmpty();
        assertThat(StaleRecordBound.overdueSince(noContract, T1, 1, StaleRecordBound.SWEEP,
                T3.plusSeconds(100_000))).isEmpty();
    }

    @Test
    @DisplayName("V-5: an asked class, N = 1 → 3600 + 660 + 2 × 5 × 1 + 0.05 s from the newest of "
            + "the four instants")
    void askedClass_nIsOne() {
        RecoveryCardRecord record = record(METERED);

        assertThat(StaleRecordBound.evidenceInstant(record, T1)).contains(T3);
        assertThat(StaleRecordBound.bound(record, 1, StaleRecordBound.SWEEP))
                .contains(Duration.ofSeconds(4270).plusMillis(50));
        assertThat(StaleRecordBound.dueBy(record, T1, 1, StaleRecordBound.SWEEP))
                .contains(T3.plusSeconds(4270).plusMillis(50));
    }

    @Test
    @DisplayName("V-5: an asked class, N = 6 → … + 60 + 0.05 s (the probes are serial)")
    void askedClass_nIsSix() {
        RecoveryCardRecord record = record(METERED);

        assertThat(StaleRecordBound.bound(record, 6, StaleRecordBound.SWEEP))
                .contains(Duration.ofSeconds(4320).plusMillis(50));
    }

    @Test
    @DisplayName("V-5: the passive class → silenceLimit + one sweep; N does not enter")
    void passiveClass() {
        RecoveryCardRecord record = record(PASSIVE);

        assertThat(StaleRecordBound.bound(record, 9, StaleRecordBound.SWEEP))
                .contains(Duration.ofSeconds(7200).plusMillis(50));
        assertThat(StaleRecordBound.dueBy(record, null, 9, StaleRecordBound.SWEEP))
                .contains(T3.plusSeconds(7200).plusMillis(50));
    }

    @Test
    @DisplayName("V-5: the evidence instant is the newest of the four — lastReported when it is "
            + "the newest, the record's instants otherwise, lastReported null tolerated")
    void evidenceIsTheNewestOfTheFour() {
        RecoveryCardRecord record = record(METERED);
        Instant later = T3.plusSeconds(1);

        assertThat(StaleRecordBound.evidenceInstant(record, later)).contains(later);
        assertThat(StaleRecordBound.evidenceInstant(record, null)).contains(T3);
        assertThat(StaleRecordBound.evidenceInstant(
                RecoveryCardRecord.empty(DEVICE).withContract(METERED, T4), null)).contains(T4);
        assertThat(StaleRecordBound.evidenceInstant(RecoveryCardRecord.empty(DEVICE), null))
                .isEmpty();
    }

    @Test
    @DisplayName("V-5: recordOverdueSince is the evidence instant only when now is AFTER dueBy — "
            + "at dueBy and before it, empty")
    void overdueSince_onlyAfterDueBy() {
        RecoveryCardRecord record = record(METERED);
        Instant dueBy = StaleRecordBound.dueBy(record, T1, 1, StaleRecordBound.SWEEP).orElseThrow();

        assertThat(StaleRecordBound.overdueSince(record, T1, 1, StaleRecordBound.SWEEP,
                dueBy.minusMillis(1))).isEmpty();
        assertThat(StaleRecordBound.overdueSince(record, T1, 1, StaleRecordBound.SWEEP, dueBy))
                .isEmpty();
        assertThat(StaleRecordBound.overdueSince(record, T1, 1, StaleRecordBound.SWEEP,
                dueBy.plusMillis(1))).isEqualTo(Optional.of(T3));
    }
}
