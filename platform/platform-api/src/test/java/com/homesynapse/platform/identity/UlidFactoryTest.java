/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.homesynapse.test.TestClock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Comprehensive tests for the {@link UlidFactory} monotonic generator.
 *
 * <p>Uses {@link TestClock} for deterministic, time-controlled testing.
 * Verifies monotonicity, clock-backward tolerance, concurrency safety,
 * and correct timestamp injection.</p>
 *
 * <p><strong>GOTCHA — static mutable state:</strong> {@code UlidFactory} maintains
 * {@code lastTimestamp}, {@code lastMsb}, and {@code lastLsb} as static fields.
 * Monotonicity is global to the JVM: if a previous test (or {@code generate()})
 * used a timestamp T, any subsequent call with a clock reporting T' &lt; T will
 * reuse T. Tests that assert on extracted timestamps must use instants far enough
 * in the future to exceed any previously used timestamp. We use a helper that
 * creates a fresh {@link TestClock} anchored 1 second after a system-clock
 * {@code generate()} call, guaranteeing our instant is the "current" high-water
 * mark.</p>
 */
@DisplayName("UlidFactory")
class UlidFactoryTest {

    /**
     * Returns a {@link TestClock} whose instant is guaranteed to be after
     * {@code UlidFactory}'s internal {@code lastTimestamp}. We achieve this by
     * generating a probe ULID (which reflects the current high-water mark),
     * extracting its timestamp, then adding 1 second plus the requested offset.
     *
     * <p>This handles arbitrary test execution order: even if a previous test
     * pushed the high-water mark far into the future (e.g., the concurrency
     * test), our clock will always be ahead of it.</p>
     */
    private static TestClock freshClock(Duration offset) {
        // Probe the factory's actual high-water mark.
        Ulid probe = UlidFactory.generate();
        Instant highWater = probe.extractTimestamp();
        Instant base = highWater.plus(Duration.ofSeconds(1)).plus(offset);
        return TestClock.at(base);
    }

    @Nested
    @DisplayName("Basic generation")
    class BasicGenerationTests {

        @Test
        @DisplayName("generate() returns non-null Ulid")
        void generateReturnsNonNull() {
            assertThat(UlidFactory.generate()).isNotNull();
        }

        @Test
        @DisplayName("generate(Clock) returns non-null Ulid")
        void generateWithClockReturnsNonNull() {
            TestClock clock = freshClock(Duration.ZERO);
            assertThat(UlidFactory.generate(clock)).isNotNull();
        }

        @Test
        @DisplayName("generated ULID has correct timestamp from provided Clock")
        void timestampMatchesClock() {
            TestClock clock = freshClock(Duration.ofHours(1));
            Instant expected = clock.peek();
            Ulid ulid = UlidFactory.generate(clock);
            assertThat(ulid.extractTimestamp()).isEqualTo(expected);
        }

        @Test
        @DisplayName("two calls at different timestamps produce different ULIDs")
        void differentTimestampsDifferentUlids() {
            TestClock clock = freshClock(Duration.ofHours(2));
            Ulid first = UlidFactory.generate(clock);
            clock.advance(Duration.ofSeconds(1));
            Ulid second = UlidFactory.generate(clock);
            assertThat(first).isNotEqualTo(second);
        }
    }

    @Nested
    @DisplayName("Monotonicity within same millisecond")
    class MonotonicityTests {

        @Test
        @DisplayName("two calls in same millisecond: second > first")
        void twoCallsSameMillisecond() {
            TestClock clock = freshClock(Duration.ofHours(3));
            Ulid first = UlidFactory.generate(clock);
            Ulid second = UlidFactory.generate(clock);
            assertThat(second.compareTo(first)).isPositive();
        }

        @Test
        @DisplayName("100 calls in same millisecond: all strictly increasing")
        void manyCallsStrictlyIncreasing() {
            TestClock clock = freshClock(Duration.ofHours(4));
            List<Ulid> ulids = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                ulids.add(UlidFactory.generate(clock));
            }
            for (int i = 1; i < ulids.size(); i++) {
                assertThat(ulids.get(i).compareTo(ulids.get(i - 1)))
                        .as("ULID at index %d should be > ULID at index %d", i, i - 1)
                        .isPositive();
            }
        }

        @Test
        @DisplayName("timestamp portion is identical for same-millisecond ULIDs")
        void sameTimestampWithinMillisecond() {
            TestClock clock = freshClock(Duration.ofHours(5));
            Instant instant = clock.peek();
            Ulid first = UlidFactory.generate(clock);
            Ulid second = UlidFactory.generate(clock);
            assertThat(first.extractTimestamp()).isEqualTo(instant);
            assertThat(second.extractTimestamp()).isEqualTo(instant);
        }
    }

    @Nested
    @DisplayName("Clock backward tolerance")
    class ClockBackwardTests {

        @Test
        @DisplayName("clock regression: second ULID still > first ULID")
        void monotonicityPreservedOnClockBackward() {
            TestClock clock = freshClock(Duration.ofHours(6));
            Ulid first = UlidFactory.generate(clock);

            // Move clock backward by 5 seconds — still after our fresh base
            clock.setFixed(clock.peek().minus(Duration.ofSeconds(5)));
            Ulid second = UlidFactory.generate(clock);

            assertThat(second.compareTo(first)).isPositive();
        }

        @Test
        @DisplayName("clock regression: previous timestamp reused, not the backward time")
        void backwardClockReusesTimestamp() {
            TestClock clock = freshClock(Duration.ofHours(7));
            Instant t1 = clock.peek();
            Ulid first = UlidFactory.generate(clock);

            // Move clock backward — factory should reuse t1, not this earlier time
            clock.setFixed(t1.minus(Duration.ofMinutes(1)));
            Ulid second = UlidFactory.generate(clock);

            assertThat(second.extractTimestamp()).isEqualTo(t1);
        }
    }

    @Nested
    @DisplayName("Concurrent generation with virtual threads")
    class ConcurrencyTests {

        @Test
        @DisplayName("20 virtual threads x 1000 ULIDs: all unique, total 20000")
        void virtualThreadConcurrency() throws InterruptedException {
            TestClock clock = freshClock(Duration.ofHours(8));
            ConcurrentLinkedQueue<Ulid> queue = new ConcurrentLinkedQueue<>();
            int threadCount = 20;
            int ulidsPerThread = 1000;

            List<Thread> threads = new ArrayList<>();
            for (int t = 0; t < threadCount; t++) {
                Thread vt = Thread.ofVirtual().start(() -> {
                    for (int i = 0; i < ulidsPerThread; i++) {
                        queue.add(UlidFactory.generate(clock));
                    }
                });
                threads.add(vt);
            }

            for (Thread thread : threads) {
                thread.join();
            }

            assertThat(queue).hasSize(threadCount * ulidsPerThread);

            Set<Ulid> unique = new HashSet<>(queue);
            assertThat(unique).hasSize(threadCount * ulidsPerThread);
        }
    }

    @Nested
    @DisplayName("Clock injection")
    class ClockInjectionTests {

        @Test
        @DisplayName("TestClock at known epoch: extractTimestamp matches")
        void knownEpoch() {
            TestClock clock = freshClock(Duration.ofHours(9));
            Instant epoch = clock.peek();
            Ulid ulid = UlidFactory.generate(clock);
            assertThat(ulid.extractTimestamp()).isEqualTo(epoch);
        }

        @Test
        @DisplayName("advancing TestClock: timestamps reflect the advance")
        void advancingClock() {
            TestClock clock = freshClock(Duration.ofHours(10));
            Instant start = clock.peek();
            Ulid first = UlidFactory.generate(clock);

            clock.advance(Duration.ofMinutes(5));
            Instant advanced = clock.peek();
            Ulid second = UlidFactory.generate(clock);

            assertThat(first.extractTimestamp()).isEqualTo(start);
            assertThat(second.extractTimestamp()).isEqualTo(advanced);
        }
    }
}
