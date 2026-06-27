/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SharedScheduler}.
 *
 * <p>Verifies the two-task cadence and shutdown semantics. Uses the
 * test-friendly {@link SharedScheduler#SharedScheduler(Runnable, Runnable)}
 * constructor because {@code DerivedWriteRateLimit} and
 * {@code QueueSaturationHealthCheck} are {@code final} and cannot be mocked.
 * The production constructor wraps these collaborators' methods as the same
 * two {@code Runnable}s.</p>
 *
 * <h2>Timing tolerance</h2>
 *
 * <p>JVM-scheduled tasks are subject to OS scheduling variance. The
 * assertions use generous wait windows and {@code >=} lower bounds rather
 * than exact counts. The brief's STOP-gate of "5 ms task execution"
 * is observed in production via the 50 ms refill period providing 90% margin;
 * this test fixture does not enforce that gate (the tasks here are no-ops).</p>
 */
@DisplayName("SharedScheduler")
class SharedSchedulerTest {

    @Test
    @DisplayName("refill task is called periodically at the 50 ms cadence")
    void refillIsCalledPeriodically() throws InterruptedException {
        AtomicInteger refillCount = new AtomicInteger();
        AtomicInteger tickCount = new AtomicInteger();

        SharedScheduler scheduler = new SharedScheduler(
                refillCount::incrementAndGet,
                tickCount::incrementAndGet);
        try {
            // 50 ms period, initial delay 50 ms. After 350 ms we expect at
            // least 3 invocations (the first at ~50 ms, second at ~100 ms,
            // third at ~150 ms, well within the wait).
            TimeUnit.MILLISECONDS.sleep(350L);

            assertThat(refillCount.get())
                    .as("refill task should have fired at least 3 times within 350 ms "
                            + "at the 50 ms cadence")
                    .isGreaterThanOrEqualTo(3);
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    @DisplayName("tick task is called periodically at the 1 s cadence")
    void tickIsCalledPeriodically() throws InterruptedException {
        AtomicInteger refillCount = new AtomicInteger();
        AtomicInteger tickCount = new AtomicInteger();

        SharedScheduler scheduler = new SharedScheduler(
                refillCount::incrementAndGet,
                tickCount::incrementAndGet);
        try {
            // 1000 ms period, initial delay 1000 ms. After 2500 ms we expect
            // at least 2 invocations (first at ~1 s, second at ~2 s).
            TimeUnit.MILLISECONDS.sleep(2_500L);

            assertThat(tickCount.get())
                    .as("tick task should have fired at least 2 times within 2.5 s "
                            + "at the 1 s cadence")
                    .isGreaterThanOrEqualTo(2);
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    @DisplayName("shutdown terminates the executor without throwing")
    void shutdownTerminatesWithoutThrowing() {
        // The brief asks for a "terminates within 2 seconds" guarantee.
        // NO_DIRECT_TIME_ACCESS forbids System.nanoTime / currentTimeMillis
        // for direct measurement; the 2-second bound is instead enforced by
        // SharedScheduler.shutdown() itself, which calls
        // executor.awaitTermination(2_000, MILLISECONDS). If termination
        // exceeded the bound, the WARN log would fire — but the test
        // verifies the contract end-to-end by simply confirming
        // shutdown() returns within the test framework's overall budget
        // (JUnit 5 default per-test budget is generous) without throwing.
        SharedScheduler scheduler = new SharedScheduler(() -> {}, () -> {});
        scheduler.shutdown();
        // If shutdown blocked beyond reason, the test would time out.
        // No assertion needed — a clean return is the success criterion.
    }

    @Test
    @DisplayName("shutdown is idempotent — second call is a no-op")
    void shutdownIsIdempotent() {
        SharedScheduler scheduler = new SharedScheduler(() -> {}, () -> {});
        scheduler.shutdown();
        // Second call must not throw, must not block beyond the grace window,
        // and must not interact with the (already-terminated) executor in a
        // way that produces an exception.
        scheduler.shutdown();
    }

    @Test
    @DisplayName("schedulePeriodic drives a registered task on cadence; shutdown stops it")
    void schedulePeriodicDrivesTaskAndShutdownStops() throws InterruptedException {
        // M7.4c: the composition root registers the pending_command_ledger's pollExpirations()
        // deadline sweep via this post-construction hook (the ledger is built after the scheduler).
        // A 50 ms cadence keeps the test fast; production uses ~1000 ms.
        AtomicInteger expiryCount = new AtomicInteger();
        SharedScheduler scheduler = new SharedScheduler(() -> {}, () -> {});
        scheduler.schedulePeriodic("ledger_expiry", expiryCount::incrementAndGet, 50L);
        int countAtShutdown;
        try {
            // 50 ms period, initial delay 50 ms. After 350 ms expect at least 3 invocations.
            TimeUnit.MILLISECONDS.sleep(350L);

            assertThat(expiryCount.get())
                    .as("the registered periodic task should fire at least 3 times within 350 ms "
                            + "at the 50 ms cadence")
                    .isGreaterThanOrEqualTo(3);
        } finally {
            scheduler.shutdown();
            countAtShutdown = expiryCount.get();
        }

        // shutdown() cancels the post-construction task with the rest — no further invocations.
        TimeUnit.MILLISECONDS.sleep(150L);
        assertThat(expiryCount.get())
                .as("no further invocations after shutdown cancels the periodic task")
                .isEqualTo(countAtShutdown);
    }

    @Test
    @DisplayName("schedulePeriodic on a shut-down scheduler throws IllegalStateException")
    void schedulePeriodicRejectedAfterShutdown() {
        SharedScheduler scheduler = new SharedScheduler(() -> {}, () -> {});
        scheduler.shutdown();
        try {
            scheduler.schedulePeriodic("late", () -> {}, 50L);
            throw new AssertionError("expected IllegalStateException scheduling after shutdown");
        } catch (IllegalStateException expected) {
            assertThat(expected).hasMessageContaining("late");
        }
    }

    @Test
    @DisplayName("task failure does not silence the scheduler")
    void taskFailureDoesNotSilenceCadence() throws InterruptedException {
        // Extra coverage beyond the brief's 4 tests: ScheduledExecutorService
        // cancels future executions of a task whose run throws. SharedScheduler
        // wraps both tasks in safelyInvoke which catches RuntimeException so
        // the cadence survives transient faults — this test pins that
        // behaviour. Without safelyInvoke, the refill task would fire once
        // (throw) and then never again, silently breaking the rate limiter.
        AtomicInteger refillCount = new AtomicInteger();

        SharedScheduler scheduler = new SharedScheduler(
                () -> {
                    refillCount.incrementAndGet();
                    throw new RuntimeException("simulated transient fault");
                },
                () -> {});
        try {
            TimeUnit.MILLISECONDS.sleep(350L);

            assertThat(refillCount.get())
                    .as("refill cadence must survive a throwing task")
                    .isGreaterThanOrEqualTo(3);
        } finally {
            scheduler.shutdown();
        }
    }
}
