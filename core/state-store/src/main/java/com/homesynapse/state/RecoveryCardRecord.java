/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

import com.homesynapse.event.AvailabilityContractDeclaredEvent;
import com.homesynapse.event.ProbeAnsweredEvent;
import com.homesynapse.platform.identity.DeviceId;

import java.time.Instant;
import java.util.Objects;

/**
 * One device's row of the recovery-card view (AMD-103 R-F; AVAIL-API-1): the newest
 * {@code probe_answered} (its instant, window, counts and the link triple the tracker kept —
 * the triple is held but never served, SPEC §12 Q12), the newest
 * {@code availability_contract_declared} (its five fields and its instant) and the newest
 * {@code availability_changed}'s instant across the device's entities. Every group is
 * {@code null} until its first record; a group is whole or absent.
 *
 * <p>Immutable; the projection replaces a row with a {@code with…} copy. Equality is
 * component-wise, which is what the catch-up-equals-replay comparison reads.</p>
 *
 * @param deviceId              the device (the key of the view), never {@code null}
 * @param probeAt               the newest record's reply instant, or {@code null}
 * @param probeWindowStart      its window's start, or {@code null}
 * @param probesAnswered        its answered count, or {@code null}
 * @param probesMissed          its missed count, or {@code null}
 * @param lqi                   its link reading's LQI, or {@code null}
 * @param rssiDbm               its link reading's RSSI in dBm, or {@code null}
 * @param linkAt                its link reading's frame instant, or {@code null}
 * @param availabilityClass     the newest contract's class token, or {@code null}
 * @param reportIntervalSeconds the newest contract's report interval, or {@code null}
 * @param silenceLimitSeconds   the newest contract's silence limit, or {@code null}
 * @param missesToDark          the newest contract's misses to dark, or {@code null}
 * @param recordIntervalSeconds the newest contract's record interval, or {@code null}
 * @param contractAt            the newest contract's instant, or {@code null}
 * @param availabilityChangedAt the newest {@code availability_changed}'s instant for the
 *                              device's entities, or {@code null}
 */
public record RecoveryCardRecord(
        DeviceId deviceId,
        Instant probeAt,
        Instant probeWindowStart,
        Integer probesAnswered,
        Integer probesMissed,
        Integer lqi,
        Integer rssiDbm,
        Instant linkAt,
        String availabilityClass,
        Integer reportIntervalSeconds,
        Integer silenceLimitSeconds,
        Integer missesToDark,
        Integer recordIntervalSeconds,
        Instant contractAt,
        Instant availabilityChangedAt
) {

    /**
     * Validates the groups: the probe quartet whole or absent, the link triple whole or absent
     * and only with a probe, the contract quartet whole or absent, the two intervals only with
     * a contract.
     */
    public RecoveryCardRecord {
        Objects.requireNonNull(deviceId, "deviceId");
        boolean anyProbe = probeAt != null || probeWindowStart != null || probesAnswered != null
                || probesMissed != null;
        boolean wholeProbe = probeAt != null && probeWindowStart != null && probesAnswered != null
                && probesMissed != null;
        if (anyProbe && !wholeProbe) {
            throw new IllegalArgumentException(
                    "probeAt, probeWindowStart, probesAnswered and probesMissed must be all null or all set");
        }
        boolean anyLink = lqi != null || rssiDbm != null || linkAt != null;
        boolean wholeLink = lqi != null && rssiDbm != null && linkAt != null;
        if (anyLink && !wholeLink) {
            throw new IllegalArgumentException("lqi, rssiDbm and linkAt must be all null or all set");
        }
        if (anyLink && !wholeProbe) {
            throw new IllegalArgumentException("a link reading needs its probe record");
        }
        boolean anyContract = availabilityClass != null || silenceLimitSeconds != null
                || missesToDark != null || contractAt != null;
        boolean wholeContract = availabilityClass != null && silenceLimitSeconds != null
                && missesToDark != null && contractAt != null;
        if (anyContract && !wholeContract) {
            throw new IllegalArgumentException(
                    "availabilityClass, silenceLimitSeconds, missesToDark and contractAt must be all null or all set");
        }
        if ((reportIntervalSeconds != null || recordIntervalSeconds != null) && !wholeContract) {
            throw new IllegalArgumentException("an interval needs its contract record");
        }
    }

    /**
     * The row before any record.
     *
     * @param deviceId the device, never {@code null}
     * @return an empty row
     */
    public static RecoveryCardRecord empty(DeviceId deviceId) {
        return new RecoveryCardRecord(deviceId, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null);
    }

    /**
     * Returns whether a {@code probe_answered} record is held.
     *
     * @return {@code true} when the probe group is set
     */
    public boolean hasProbe() {
        return probeAt != null;
    }

    /**
     * Returns whether an {@code availability_contract_declared} record is held.
     *
     * @return {@code true} when the contract group is set
     */
    public boolean hasContract() {
        return availabilityClass != null;
    }

    /**
     * Returns whether the newest contract names an asked class (R-G's N counts these).
     *
     * @return {@code true} for {@code mains-metered} and {@code mains-floor}
     */
    public boolean asked() {
        return AvailabilityContractDeclaredEvent.AvailabilityClass.fromToken(availabilityClass)
                .map(AvailabilityContractDeclaredEvent.AvailabilityClass::asked)
                .orElse(false);
    }

    /**
     * The row with the probe group replaced by the record's.
     *
     * @param probe the {@code probe_answered} payload, never {@code null}
     * @return the new row
     */
    public RecoveryCardRecord withProbe(ProbeAnsweredEvent probe) {
        Objects.requireNonNull(probe, "probe");
        return new RecoveryCardRecord(deviceId, probe.at(), probe.windowStart(),
                probe.answeredSinceLastRecord(), probe.missedSinceLastRecord(), probe.lqi(),
                probe.rssiDbm(), probe.linkAt(), availabilityClass, reportIntervalSeconds,
                silenceLimitSeconds, missesToDark, recordIntervalSeconds, contractAt,
                availabilityChangedAt);
    }

    /**
     * The row with the contract group replaced by the record's.
     *
     * @param contract the {@code availability_contract_declared} payload, never {@code null}
     * @param at       the declaration's instant, never {@code null}
     * @return the new row
     */
    public RecoveryCardRecord withContract(AvailabilityContractDeclaredEvent contract, Instant at) {
        Objects.requireNonNull(contract, "contract");
        Objects.requireNonNull(at, "at");
        return new RecoveryCardRecord(deviceId, probeAt, probeWindowStart, probesAnswered,
                probesMissed, lqi, rssiDbm, linkAt, contract.availabilityClass(),
                contract.reportIntervalSeconds(), contract.silenceLimitSeconds(),
                contract.missesToDark(), contract.recordIntervalSeconds(), at,
                availabilityChangedAt);
    }

    /**
     * The row with the newest {@code availability_changed} instant replaced.
     *
     * @param at the transition's instant, never {@code null}
     * @return the new row
     */
    public RecoveryCardRecord withAvailabilityChangedAt(Instant at) {
        Objects.requireNonNull(at, "at");
        return new RecoveryCardRecord(deviceId, probeAt, probeWindowStart, probesAnswered,
                probesMissed, lqi, rssiDbm, linkAt, availabilityClass, reportIntervalSeconds,
                silenceLimitSeconds, missesToDark, recordIntervalSeconds, contractAt, at);
    }
}
