/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for {@link TimeConversion}.
 *
 * <p>Verifies the {@link Instant} ↔ microsecond-long conversion helpers that
 * the SQLite event store uses to bind the {@code ingest_time} and
 * {@code event_time} integer columns (Doc 01 §4.2 / LTD-08). Covers the
 * documented behaviors: microsecond-truncation of sub-microsecond nanoseconds,
 * round-trip at microsecond granularity, pre-epoch (negative) handling, and
 * null-tolerant variants for the nullable {@code event_time} column.</p>
 */
@DisplayName("TimeConversion")
final class TimeConversionTest {

    /** Creates a new test instance. */
    TimeConversionTest() {
        // Explicit no-arg constructor for -Xlint:all -Werror builds.
    }

    @Test
    @DisplayName("epoch instant round-trips to 0 micros")
    void epochInstant_roundTripsToZero() {
        Instant epoch = Instant.EPOCH;

        long micros = TimeConversion.toMicros(epoch);
        Instant decoded = TimeConversion.fromMicros(micros);

        assertThat(micros).isZero();
        assertThat(decoded).isEqualTo(epoch);
    }

    @Test
    @DisplayName("microsecond-precise instant round-trips losslessly")
    void microsecondPreciseInstant_roundTripsLosslessly() {
        // 123_456_000 nanoseconds = 123_456 microseconds, exactly
        // representable by the integer column.
        Instant original = Instant.ofEpochSecond(1_700_000_000L, 123_456_000L);

        long micros = TimeConversion.toMicros(original);
        Instant decoded = TimeConversion.fromMicros(micros);

        assertThat(decoded).isEqualTo(original);
        assertThat(micros).isEqualTo(1_700_000_000L * 1_000_000L + 123_456L);
    }

    @Test
    @DisplayName("sub-microsecond nanoseconds are truncated toward zero")
    void subMicrosecondNanos_areTruncated() {
        // 500 nanoseconds is below the microsecond boundary and must be
        // truncated, not rounded.
        Instant subMicro = Instant.ofEpochSecond(42L, 500L);

        long micros = TimeConversion.toMicros(subMicro);

        // 42 seconds × 1,000,000 + 0 microseconds
        assertThat(micros).isEqualTo(42L * 1_000_000L);
    }

    @Test
    @DisplayName("round-tripping a nanosecond-precise instant loses sub-microsecond precision")
    void roundTrip_losesSubMicrosecondPrecision() {
        Instant nanosPrecise = Instant.ofEpochSecond(1_700_000_000L, 123_456_789L);

        Instant decoded = TimeConversion.fromMicros(TimeConversion.toMicros(nanosPrecise));

        // 789 ns is truncated — decoded must match the instant rounded down
        // to the microsecond.
        assertThat(decoded).isEqualTo(Instant.ofEpochSecond(1_700_000_000L, 123_456_000L));
    }

    @Test
    @DisplayName("pre-epoch instants produce negative micros and round-trip correctly")
    void preEpochInstant_handledCorrectly() {
        // One second before the epoch with microsecond precision.
        Instant preEpoch = Instant.ofEpochSecond(-1L, 500_000_000L); // -0.5s

        long micros = TimeConversion.toMicros(preEpoch);
        Instant decoded = TimeConversion.fromMicros(micros);

        assertThat(micros).isEqualTo(-500_000L);
        assertThat(decoded).isEqualTo(preEpoch);
    }

    @Test
    @DisplayName("toMicros rejects null with a helpful message")
    void toMicros_nullInput_throwsNpe() {
        assertThatNullPointerException()
                .isThrownBy(() -> TimeConversion.toMicros(null))
                .withMessageContaining("instant");
    }

    @Test
    @DisplayName("toMicrosOrNull returns null for null input")
    void toMicrosOrNull_nullInput_returnsNull() {
        assertThat(TimeConversion.toMicrosOrNull(null)).isNull();
    }

    @Test
    @DisplayName("toMicrosOrNull delegates to toMicros for a non-null input")
    void toMicrosOrNull_nonNullInput_matchesToMicros() {
        Instant input = Instant.ofEpochSecond(100L, 250_000_000L);

        assertThat(TimeConversion.toMicrosOrNull(input))
                .isEqualTo(TimeConversion.toMicros(input));
    }

    @Test
    @DisplayName("fromMicrosOrNull returns null for null input")
    void fromMicrosOrNull_nullInput_returnsNull() {
        assertThat(TimeConversion.fromMicrosOrNull(null)).isNull();
    }

    @Test
    @DisplayName("fromMicrosOrNull delegates to fromMicros for a non-null input")
    void fromMicrosOrNull_nonNullInput_matchesFromMicros() {
        long micros = 1_700_000_000_123_456L;

        assertThat(TimeConversion.fromMicrosOrNull(micros))
                .isEqualTo(TimeConversion.fromMicros(micros));
    }
}
