/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

package com.homesynapse.platform.identity;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe, monotonic ULID generator.
 *
 * <p>Generates {@link Ulid} values that are strictly monotonically increasing within
 * the same JVM process. Within a single millisecond, successive calls increment the
 * 80-bit random component rather than generating a new random value, guaranteeing
 * sort-order consistency with generation order.</p>
 *
 * <p>Thread safety is achieved via {@link ReentrantLock} rather than {@code synchronized}
 * to avoid pinning virtual threads to carrier threads (LTD-11). Randomness is sourced
 * from {@link SecureRandom}.</p>
 *
 * <p><strong>Clock backward tolerance:</strong> if the system clock moves backward
 * (e.g., NTP adjustment), the generator continues using the previous timestamp to
 * maintain the monotonicity guarantee.</p>
 *
 * @see Ulid
 */
public final class UlidFactory {

    private static final ReentrantLock LOCK = new ReentrantLock();
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Timestamp from the most recent generation, in epoch milliseconds. */
    private static long lastTimestamp;

    /** Most significant 64 bits of the most recently generated ULID. */
    private static long lastMsb;

    /** Least significant 64 bits of the most recently generated ULID. */
    private static long lastLsb;

    private UlidFactory() {
        // Utility class — no instantiation.
    }

    /**
     * Generates a monotonically increasing ULID using the system clock.
     *
     * <p>Equivalent to {@code generate(Clock.systemUTC())}.</p>
     *
     * @return a new {@link Ulid}, never {@code null}
     * @throws IllegalStateException if the 80-bit random component overflows within
     *                               a single millisecond (requires &gt;2<sup>80</sup>
     *                               generations in one millisecond)
     */
    public static Ulid generate() {
        return generate(Clock.systemUTC());
    }

    /**
     * Generates a monotonically increasing ULID using the supplied clock.
     *
     * <p>The clock parameter supports deterministic testing. In production, pass
     * {@link Clock#systemUTC()} or use the no-arg {@link #generate()} overload.</p>
     *
     * <p>Within the same millisecond, successive calls increment the 80-bit random
     * component by one. If the clock reports a time earlier than the previous generation,
     * the previous timestamp is reused (monotonicity guarantee). A fresh random component
     * is generated only when the timestamp advances to a new millisecond.</p>
     *
     * @param clock the clock to read the current timestamp from, never {@code null}
     * @return a new {@link Ulid}, never {@code null}
     * @throws NullPointerException  if {@code clock} is {@code null}
     * @throws IllegalStateException if the 80-bit random component overflows within
     *                               a single millisecond
     */
    public static Ulid generate(Clock clock) {
        Objects.requireNonNull(clock, "clock must not be null");
        LOCK.lock();
        try {
            long timestamp = clock.millis();

            if (timestamp <= lastTimestamp) {
                timestamp = lastTimestamp;
            }

            long msb;
            long lsb;

            if (timestamp == lastTimestamp) {
                lsb = lastLsb + 1;
                msb = lastMsb;
                if (lsb == 0) {
                    long randomHigh = (msb & 0xFFFFL) + 1;
                    if (randomHigh > 0xFFFFL) {
                        throw new IllegalStateException(
                                "ULID random component overflow within millisecond "
                                        + timestamp);
                    }
                    msb = (msb & 0xFFFFFFFFFFFF0000L) | randomHigh;
                }
            } else {
                msb = (timestamp << 16) | (RANDOM.nextLong() & 0xFFFFL);
                lsb = RANDOM.nextLong();
            }

            lastTimestamp = timestamp;
            lastMsb = msb;
            lastLsb = lsb;

            return new Ulid(msb, lsb);
        } finally {
            LOCK.unlock();
        }
    }
}
