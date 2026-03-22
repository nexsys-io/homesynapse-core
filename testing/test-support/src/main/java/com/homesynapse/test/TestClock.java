/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A controllable {@link Clock} for deterministic testing of time-dependent code.
 *
 * <p>All HomeSynapse interfaces that depend on time accept {@code Clock} as a constructor
 * parameter. In production, {@code Clock.systemUTC()} is used. In tests, inject a
 * {@code TestClock} and control time explicitly.
 *
 * <p>Thread-safe: the internal instant is stored in an {@link AtomicReference}.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * var clock = TestClock.at(Instant.parse("2026-01-15T10:00:00Z"));
 * var service = new TimeBasedService(clock);
 *
 * // Time is frozen at 10:00:00
 * assertThat(service.currentTime()).isEqualTo("2026-01-15T10:00:00Z");
 *
 * // Advance by 5 minutes
 * clock.advance(Duration.ofMinutes(5));
 * assertThat(service.currentTime()).isEqualTo("2026-01-15T10:05:00Z");
 * }</pre>
 *
 * @see java.time.Clock
 */
public final class TestClock extends Clock {

    private final AtomicReference<Instant> currentInstant;
    private final ZoneId zone;

    private TestClock(Instant initial, ZoneId zone) {
        this.currentInstant = new AtomicReference<>(Objects.requireNonNull(initial, "initial"));
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    /**
     * Creates a TestClock fixed at the given instant in UTC.
     *
     * @param instant the initial time
     * @return a new TestClock
     */
    public static TestClock at(Instant instant) {
        return new TestClock(instant, ZoneId.of("UTC"));
    }

    /**
     * Creates a TestClock fixed at the given instant in the specified zone.
     *
     * @param instant the initial time
     * @param zone    the time zone
     * @return a new TestClock
     */
    public static TestClock at(Instant instant, ZoneId zone) {
        return new TestClock(instant, zone);
    }

    /**
     * Creates a TestClock fixed at {@code 2026-01-01T00:00:00Z}.
     * Convenient default for tests that don't care about the specific time.
     *
     * @return a new TestClock at the epoch-like default
     */
    public static TestClock createDefault() {
        return at(Instant.parse("2026-01-01T00:00:00Z"));
    }

    /**
     * Advances the clock by the given duration.
     *
     * @param duration the amount to advance (must be non-negative)
     * @return the new instant after advancing
     * @throws IllegalArgumentException if duration is negative
     */
    public Instant advance(Duration duration) {
        if (duration.isNegative()) {
            throw new IllegalArgumentException("Cannot advance by negative duration: " + duration);
        }
        return currentInstant.updateAndGet(current -> current.plus(duration));
    }

    /**
     * Sets the clock to a specific instant, replacing the current time.
     *
     * @param instant the new time
     */
    public void setFixed(Instant instant) {
        currentInstant.set(Objects.requireNonNull(instant, "instant"));
    }

    /**
     * Returns the current instant without advancing.
     *
     * @return the current frozen instant
     */
    public Instant peek() {
        return currentInstant.get();
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new TestClock(currentInstant.get(), zone);
    }

    @Override
    public Instant instant() {
        return currentInstant.get();
    }

    @Override
    public String toString() {
        return "TestClock[" + currentInstant.get() + "]";
    }
}
