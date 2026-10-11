/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import java.time.Instant;
import java.util.Objects;

/**
 * The sampled record of an answered liveness probe (AMD-103 R-B; AVAIL-API-1).
 *
 * <p>Subject: the DEVICE, never an entity — one record per device. Produced by an
 * integration's availability tracker at most once per device per record interval
 * within one tracker instance, measured on a monotonic source; the earliest answered
 * probe after a boot or an integration restart is always recorded. The window is
 * never persisted: a window open at a stop is lost with its counts.</p>
 *
 * <p>Priority: DIAGNOSTIC, ELEVATED one level to NORMAL on an instance whose window
 * holds a miss ({@code missedSinceLastRecord > 0}) — declared at production and
 * recorded in the persisted event (Doc 01 §3.3). Category {@code device_health}
 * (R-A). Schema version 1.</p>
 *
 * <p><strong>Invariants:</strong> {@code at} and {@code windowStart} are set and
 * {@code windowStart} is not after {@code at}; the counts are non-negative and
 * {@code answeredSinceLastRecord} is at least 1 (the recorded answer counts in it);
 * {@code lqi}, {@code rssiDbm} and {@code linkAt} are all {@code null} or all set —
 * the LAST link reading the tracker kept for the device with that reading's own
 * frame instant (a probe reply reaches the tracker without a reading, so
 * {@code linkAt} is not {@code at}).</p>
 *
 * @param at                     the answered probe's reply instant (wall clock)
 * @param windowStart            the window's start: {@code at} minus the window's
 *                               monotonic length — the previous record's {@code at},
 *                               or the tracker's start for the opening record
 * @param answeredSinceLastRecord the probes of this device answered in the window,
 *                               the recorded answer included; at least 1
 * @param missedSinceLastRecord  the probes of this device missed in the window; a
 *                               dark verdict stays {@code availability_changed}'s
 * @param lqi                    the last link reading's LQI (0..255), or {@code null}
 * @param rssiDbm                the last link reading's RSSI in dBm, or {@code null}
 * @param linkAt                 the receipt instant of the frame that delivered the
 *                               reading, or {@code null}
 */
@EventType(EventTypes.PROBE_ANSWERED)
public record ProbeAnsweredEvent(
        Instant at,
        Instant windowStart,
        int answeredSinceLastRecord,
        int missedSinceLastRecord,
        Integer lqi,
        Integer rssiDbm,
        Instant linkAt
) implements DomainEvent {

    /**
     * Constructs a ProbeAnsweredEvent with validation.
     *
     * @param at                      the reply instant, not null
     * @param windowStart             the window's start, not null, not after {@code at}
     * @param answeredSinceLastRecord the answered count, at least 1
     * @param missedSinceLastRecord   the missed count, at least 0
     * @param lqi                     the last link reading's LQI, nullable
     * @param rssiDbm                 the last link reading's RSSI in dBm, nullable
     * @param linkAt                  the reading's frame instant, nullable
     */
    public ProbeAnsweredEvent {
        Objects.requireNonNull(at, "at cannot be null");
        Objects.requireNonNull(windowStart, "windowStart cannot be null");
        if (windowStart.isAfter(at)) {
            throw new IllegalArgumentException(
                    "windowStart must not be after at: windowStart=" + windowStart + ", at=" + at);
        }
        if (answeredSinceLastRecord < 1) {
            throw new IllegalArgumentException(
                    "answeredSinceLastRecord must be >= 1, got " + answeredSinceLastRecord);
        }
        if (missedSinceLastRecord < 0) {
            throw new IllegalArgumentException(
                    "missedSinceLastRecord must be >= 0, got " + missedSinceLastRecord);
        }
        boolean anyLink = lqi != null || rssiDbm != null || linkAt != null;
        boolean wholeLink = lqi != null && rssiDbm != null && linkAt != null;
        if (anyLink && !wholeLink) {
            throw new IllegalArgumentException(
                    "lqi, rssiDbm and linkAt must be all null or all set");
        }
    }
}
