/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event.bus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DerivedWriteRateLimit} (AMD-43 §3.6.4).
 *
 * <p>Tests drive {@link DerivedWriteRateLimit#refill()} directly for
 * deterministic timing rather than relying on a real
 * {@code ScheduledExecutorService}. The token bucket primitive is
 * independently testable per DEC-M3-15 — it has no compile-time dependency
 * on StateProjection.</p>
 */
class DerivedWriteRateLimitTest {

    private static final String SUB_ID = "state-projection";
    private static final Instant EPOCH = Instant.parse("2026-05-01T00:00:00Z");

    private RecordingMetrics metrics;
    private Clock clock;

    /** Creates a new test instance. */
    DerivedWriteRateLimitTest() {
        // Explicit constructor per -Xlint:all -Werror requirement.
    }

    @BeforeEach
    void setUp() {
        metrics = new RecordingMetrics();
        clock = Clock.fixed(EPOCH, ZoneOffset.UTC);
    }

    @Test
    void acquireSucceedsWhenTokensAvailable() throws InterruptedException {
        DerivedWriteRateLimit limit = new DerivedWriteRateLimit(
                10, clock, metrics, SUB_ID);

        limit.acquire();

        assertThat(metrics.accepted).containsExactly(SUB_ID);
        assertThat(metrics.parked).isEmpty();
        assertThat(limit.available()).isEqualTo(9);
        limit.close();
    }

    @Test
    void acquireParksWhenBucketEmpty() throws InterruptedException {
        DerivedWriteRateLimit limit = new DerivedWriteRateLimit(
                2, clock, metrics, SUB_ID);

        // Drain the bucket.
        limit.acquire();
        limit.acquire();
        assertThat(limit.available()).isEqualTo(0);
        assertThat(metrics.accepted).hasSize(2);
        metrics.accepted.clear();

        // The third acquire must park. Run it on a separate thread.
        AtomicInteger acquired = new AtomicInteger(0);
        Thread t = Thread.ofVirtual().start(() -> {
            try {
                limit.acquire();
                acquired.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // Wait long enough for the thread to park.
        waitUntilParked(limit);
        assertThat(acquired.get())
                .as("Thread should still be parked")
                .isEqualTo(0);
        assertThat(metrics.parked).containsExactly(SUB_ID);

        // Refill — wakes the parked thread.
        limit.refill();

        t.join(2_000);
        assertThat(t.isAlive()).as("Parked thread should have completed").isFalse();
        assertThat(acquired.get()).isEqualTo(1);
        assertThat(metrics.accepted).containsExactly(SUB_ID);
        limit.close();
    }

    @Test
    void refillAdds10TokensPer50ms() throws InterruptedException {
        DerivedWriteRateLimit limit = new DerivedWriteRateLimit(
                200, clock, metrics, SUB_ID);

        // Drain the bucket completely.
        for (int i = 0; i < 200; i++) {
            limit.acquire();
        }
        assertThat(limit.available()).isEqualTo(0);

        limit.refill();

        assertThat(limit.available())
                .as("One refill tick adds 10 tokens")
                .isEqualTo(10);
        limit.close();
    }

    @Test
    void refillCapsAtCapacity() {
        DerivedWriteRateLimit limit = new DerivedWriteRateLimit(
                200, clock, metrics, SUB_ID);

        // Bucket starts full at capacity. Refill must not overflow.
        limit.refill();
        limit.refill();

        assertThat(limit.available()).isEqualTo(200);
        limit.close();
    }

    @Test
    void concurrentAcquireIsThreadSafe() throws InterruptedException {
        // Capacity exceeds total demand so no CAS racer ever sees zero tokens
        // and parks. The test focuses on AtomicInteger correctness under
        // contention rather than the empty-bucket boundary.
        int capacity = 400;
        int threadCount = 20;
        int acquiresPerThread = 10;
        int totalAcquires = threadCount * acquiresPerThread;

        DerivedWriteRateLimit limit = new DerivedWriteRateLimit(
                capacity, clock, metrics, SUB_ID);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        List<Throwable> errors = new CopyOnWriteArrayList<>();

        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < acquiresPerThread; i++) {
                        limit.acquire();
                    }
                } catch (Throwable err) {
                    errors.add(err);
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS))
                .as("All acquires complete without deadlock")
                .isTrue();
        assertThat(errors).isEmpty();
        assertThat(metrics.accepted)
                .as("Exactly totalAcquires accepts recorded — no double-count, no loss")
                .hasSize(totalAcquires);
        assertThat(metrics.parked)
                .as("No parks under sufficient-capacity contention")
                .isEmpty();
        assertThat(limit.available())
                .as("Remaining tokens = capacity - totalAcquires")
                .isEqualTo(capacity - totalAcquires);
        executor.shutdown();
        limit.close();
    }

    @Test
    void closeReleasesParkedThreads() throws InterruptedException {
        DerivedWriteRateLimit limit = new DerivedWriteRateLimit(
                1, clock, metrics, SUB_ID);

        // Drain.
        limit.acquire();

        AtomicInteger acquired = new AtomicInteger(0);
        Thread t = Thread.ofVirtual().start(() -> {
            try {
                limit.acquire();
                acquired.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        waitUntilParked(limit);

        limit.close();
        t.join(2_000);
        assertThat(t.isAlive())
                .as("close() must release parked threads")
                .isFalse();
    }

    @Test
    void metricsRecordCorrectSubscriberId() throws InterruptedException {
        DerivedWriteRateLimit limit = new DerivedWriteRateLimit(
                1, clock, metrics, "test-sub");

        // First acquire — accepted with subscriberId.
        limit.acquire();
        assertThat(metrics.accepted).containsExactly("test-sub");

        // Park a second acquire.
        Thread t = Thread.ofVirtual().start(() -> {
            try {
                limit.acquire();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        });
        waitUntilParked(limit);
        assertThat(metrics.parked).containsExactly("test-sub");
        limit.close();
        t.join(2_000);
    }

    @Test
    void defaultCapacityConstructor() throws InterruptedException {
        DerivedWriteRateLimit limit = new DerivedWriteRateLimit(
                clock, metrics, SUB_ID);
        assertThat(limit.capacity()).isEqualTo(DerivedWriteRateLimit.DEFAULT_CAPACITY);
        assertThat(limit.available()).isEqualTo(DerivedWriteRateLimit.DEFAULT_CAPACITY);
        limit.close();
    }

    /**
     * Polls until a parked-metric record appears in {@link #metrics}, or fails
     * after a 2-second budget. Uses 10 ms polling — no direct time source.
     */
    private void waitUntilParked(DerivedWriteRateLimit limit)
            throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            if (!metrics.parked.isEmpty()) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Thread did not park within 2 s — bucket available="
                + limit.available());
    }

    /**
     * Test double recording metric calls for assertions. Counters are exposed
     * as {@code CopyOnWriteArrayList} so threads on the contended paths can
     * record concurrently without external synchronization.
     */
    private static final class RecordingMetrics implements BusMetrics {

        final List<String> accepted = new CopyOnWriteArrayList<>();
        final List<String> parked = new CopyOnWriteArrayList<>();

        @Override
        public void recordPublishLatency(Duration duration) {
            // Not exercised in these tests.
        }

        @Override
        public void incrementPublisherBlocked() {
            // Not exercised in these tests.
        }

        @Override
        public void recordWriterQueueDepth(int depth) {
            // Not exercised in these tests.
        }

        @Override
        public void recordSubscriberLag(String subscriberId, long lagEvents, Duration lagMillis) {
            // Not exercised in these tests.
        }

        @Override
        public void recordDerivedWriteAccepted(String subscriberId) {
            accepted.add(subscriberId);
        }

        @Override
        public void recordDerivedWriteParked(String subscriberId) {
            parked.add(subscriberId);
        }
    }
}
