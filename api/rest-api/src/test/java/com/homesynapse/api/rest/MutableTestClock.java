/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * Mutable test clock for the idempotency TTL boundary tests.
 *
 * <p>Local to this package because rest-api's test tree has no
 * {@code testing/test-support} dependency; mirrors that module's
 * {@code MutableClock} surface ({@link #advance(Duration)}) at the minimal
 * size these tests need. Makes no banned time calls
 * ({@code NO_DIRECT_TIME_ACCESS}-clean): the instant is set explicitly and
 * only ever moves via {@link #advance(Duration)}.</p>
 *
 * <p>Test-only — not part of the rest-api module's exported API.</p>
 */
final class MutableTestClock extends Clock {

    private Instant current;

    /**
     * Constructs a clock frozen at the given instant.
     *
     * @param start the initial instant; never {@code null}
     */
    MutableTestClock(Instant start) {
        this.current = Objects.requireNonNull(start, "start");
    }

    /** Moves the clock forward by the given duration. */
    void advance(Duration duration) {
        current = current.plus(Objects.requireNonNull(duration, "duration"));
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("fixed-zone test clock");
    }

    @Override
    public Instant instant() {
        return current;
    }
}
