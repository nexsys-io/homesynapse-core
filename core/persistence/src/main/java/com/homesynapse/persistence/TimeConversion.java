/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import java.time.Instant;

/**
 * Static helpers converting between {@link Instant} (Java representation of
 * timestamps) and {@code long} microseconds-since-epoch (SQLite storage
 * representation) for the domain event store (Doc 01 §4.2, LTD-08).
 *
 * <p>The domain event store persists {@code ingest_time} and {@code event_time}
 * as {@code INTEGER} columns holding microseconds since the Unix epoch. This
 * trades the {@link Instant} nanosecond resolution for a compact fixed-width
 * representation that fits SQLite's native 64-bit integer type, matches the
 * {@code idx_events_event_time} index key directly, and is trivial to compare
 * and range-scan in SQL without string parsing.</p>
 *
 * <p><strong>Lossy conversion:</strong> {@link Instant} carries nanosecond
 * precision; microseconds is coarser. The {@link #toMicros(Instant)} helper
 * truncates (not rounds) by dividing the nanosecond-of-second by 1_000, which
 * is the same semantics the SQLite JDBC driver uses when a JDBC timestamp is
 * bound as a microsecond integer. No event record in HomeSynapse relies on
 * sub-microsecond resolution, so the truncation is imperceptible to
 * subscribers.</p>
 *
 * <p><strong>Null handling:</strong> The {@code ingest_time} column is
 * {@code NOT NULL} and maps to a {@link Instant} that is always present; the
 * {@code event_time} column is nullable and maps to a nullable
 * {@link Instant}. The {@code *OrNull} variants handle the nullable case so
 * that callers do not have to branch at every use site.</p>
 *
 * <p>Package-private — internal persistence infrastructure, not public API.</p>
 */
final class TimeConversion {

    /** Microseconds per second — conversion factor for {@link Instant} decomposition. */
    private static final long MICROS_PER_SECOND = 1_000_000L;

    /** Nanoseconds per microsecond — truncation factor for the sub-second field. */
    private static final long NANOS_PER_MICRO = 1_000L;

    private TimeConversion() {
        // Utility class — non-instantiable
    }

    /**
     * Converts an {@link Instant} to microseconds since the Unix epoch.
     *
     * <p>Sub-microsecond nanoseconds are truncated toward zero. For instants
     * before the epoch the result is negative; SQLite sorts signed 64-bit
     * integers correctly, so the {@code idx_events_event_time} index still
     * produces chronological scans for such values.</p>
     *
     * @param instant the instant to convert; never {@code null}
     * @return microseconds since the epoch (may be negative for pre-epoch instants)
     * @throws NullPointerException if {@code instant} is {@code null}
     */
    static long toMicros(Instant instant) {
        if (instant == null) {
            throw new NullPointerException("instant must not be null");
        }
        long seconds = instant.getEpochSecond();
        long nanos = instant.getNano();
        return Math.multiplyExact(seconds, MICROS_PER_SECOND) + (nanos / NANOS_PER_MICRO);
    }

    /**
     * Converts microseconds since the Unix epoch back to an {@link Instant}.
     *
     * <p>The resulting {@link Instant} has zero nanoseconds below the
     * microsecond boundary. Round-tripping an {@link Instant} through
     * {@link #toMicros(Instant)} and {@link #fromMicros(long)} loses any
     * sub-microsecond precision present in the original value.</p>
     *
     * @param micros microseconds since the epoch (may be negative)
     * @return the corresponding {@link Instant}, never {@code null}
     */
    static Instant fromMicros(long micros) {
        long seconds = Math.floorDiv(micros, MICROS_PER_SECOND);
        long remainderMicros = Math.floorMod(micros, MICROS_PER_SECOND);
        long nanosOfSecond = remainderMicros * NANOS_PER_MICRO;
        return Instant.ofEpochSecond(seconds, nanosOfSecond);
    }

    /**
     * Null-tolerant variant of {@link #toMicros(Instant)}.
     *
     * <p>Returns {@code null} for a {@code null} input, suitable for binding
     * the nullable {@code event_time} column via {@link java.sql.PreparedStatement#setObject}.</p>
     *
     * @param instant the instant to convert, or {@code null}
     * @return a boxed {@link Long} of microseconds, or {@code null} if the input was {@code null}
     */
    static Long toMicrosOrNull(Instant instant) {
        return instant == null ? null : toMicros(instant);
    }

    /**
     * Null-tolerant variant of {@link #fromMicros(long)}.
     *
     * <p>Returns {@code null} for a {@code null} input, suitable for decoding
     * the nullable {@code event_time} column read via
     * {@link java.sql.ResultSet#getObject}.</p>
     *
     * @param micros a boxed microsecond value, or {@code null}
     * @return the corresponding {@link Instant}, or {@code null} if the input was {@code null}
     */
    static Instant fromMicrosOrNull(Long micros) {
        return micros == null ? null : fromMicros(micros);
    }
}
