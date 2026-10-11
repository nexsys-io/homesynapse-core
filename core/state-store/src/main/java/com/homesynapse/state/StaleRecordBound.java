/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * R-G's stale-record bound (AMD-103; AVAIL-API-1 §4.6), computed at READ in this one place —
 * never re-implemented in {@code rest-api} and never on the dashboard (SPEC §12 Q10).
 *
 * <p><strong>The evidence instant</strong> is the newest of the state projection's
 * {@code lastReported}, the record's {@code probeAt}, its newest {@code availabilityChangedAt}
 * and its contract's instant (a boot's declaration restarts the bound, so a quiet device does
 * not read stale from a restart until its opening probe). <strong>The bound</strong> is the
 * longest a live tracker can leave a device without a newer record: for an asked class
 * {@code recordIntervalSeconds + silenceLimitSeconds + missesToDark × PROBE_DEADLINE × N +
 * one sweep} (N = the devices whose newest contract names an asked class — the probes are
 * serial on the run thread), for the passive class {@code silenceLimitSeconds + one sweep}.
 * {@code dueBy = evidence + bound}; {@code recordOverdueSince} is the evidence instant once
 * {@code now} is past {@code dueBy}. With no contract record the bound is not computed and
 * the sixth cell is not sayable.</p>
 *
 * <p>The sweep and the deadline live in the Zigbee adapter, which this module must not
 * require: they are carried here as this class's own constants, each with its citation — a
 * duplicated value, never a module edge.</p>
 */
public final class StaleRecordBound {

    /**
     * One run cycle — {@code ZigbeeIntegrationAdapter.PRODUCTION_CYCLE_MILLIS = 50}
     * (the adapter's §5.1 chosen constant; the 0.05 s of the tracker's acceptance arithmetic).
     */
    public static final Duration SWEEP = Duration.ofMillis(50);

    /**
     * The probe's exchange deadline — {@code ZigbeeIntegrationAdapter.AVAILABILITY_PING_TIMEOUT_MILLIS
     * = 5_000} (M9.6-AVAIL DP-4; the 5 s of the tracker's acceptance arithmetic).
     */
    public static final Duration PROBE_DEADLINE = Duration.ofSeconds(5);

    private StaleRecordBound() {
        // Utility class — no instances.
    }

    /**
     * The newest of the four instants.
     *
     * @param record       the device's row, never {@code null}
     * @param lastReported the state projection's {@code lastReported}, or {@code null}
     * @return the evidence instant, or empty when none of the four is set
     */
    public static Optional<Instant> evidenceInstant(RecoveryCardRecord record, Instant lastReported) {
        Objects.requireNonNull(record, "record");
        Instant newest = lastReported;
        newest = newer(newest, record.probeAt());
        newest = newer(newest, record.availabilityChangedAt());
        newest = newer(newest, record.contractAt());
        return Optional.ofNullable(newest);
    }

    /**
     * The bound from the record's newest contract.
     *
     * @param record     the device's row, never {@code null}
     * @param askedCount N — the devices whose newest contract names an asked class
     * @param sweep      one run cycle ({@link #SWEEP} in production)
     * @return the bound, or empty with no contract record
     */
    public static Optional<Duration> bound(RecoveryCardRecord record, int askedCount, Duration sweep) {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(sweep, "sweep");
        if (!record.hasContract()) {
            return Optional.empty();
        }
        Duration silence = Duration.ofSeconds(record.silenceLimitSeconds());
        if (!record.asked()) {
            return Optional.of(silence.plus(sweep));
        }
        int recordInterval = record.recordIntervalSeconds() == null ? 0 : record.recordIntervalSeconds();
        Duration probes = PROBE_DEADLINE.multipliedBy((long) record.missesToDark() * askedCount);
        return Optional.of(Duration.ofSeconds(recordInterval).plus(silence).plus(probes).plus(sweep));
    }

    /**
     * The instant by which a newer record is due: the evidence instant plus the bound.
     *
     * @param record       the device's row, never {@code null}
     * @param lastReported the state projection's {@code lastReported}, or {@code null}
     * @param askedCount   N
     * @param sweep        one run cycle
     * @return the due instant, or empty with no contract record
     */
    public static Optional<Instant> dueBy(RecoveryCardRecord record, Instant lastReported,
            int askedCount, Duration sweep) {
        Optional<Duration> bound = bound(record, askedCount, sweep);
        Optional<Instant> evidence = evidenceInstant(record, lastReported);
        if (bound.isEmpty() || evidence.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(evidence.get().plus(bound.get()));
    }

    /**
     * The sixth cell's instant: the evidence instant when {@code now} is after the due instant.
     *
     * @param record       the device's row, never {@code null}
     * @param lastReported the state projection's {@code lastReported}, or {@code null}
     * @param askedCount   N
     * @param sweep        one run cycle
     * @param now          the core's clock, never {@code null}
     * @return the evidence instant when overdue, else empty
     */
    public static Optional<Instant> overdueSince(RecoveryCardRecord record, Instant lastReported,
            int askedCount, Duration sweep, Instant now) {
        Objects.requireNonNull(now, "now");
        Optional<Instant> dueBy = dueBy(record, lastReported, askedCount, sweep);
        if (dueBy.isEmpty() || !now.isAfter(dueBy.get())) {
            return Optional.empty();
        }
        return evidenceInstant(record, lastReported);
    }

    private static Instant newer(Instant current, Instant candidate) {
        if (candidate == null) {
            return current;
        }
        return current == null || candidate.isAfter(current) ? candidate : current;
    }
}
