/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.lifecycle;

import com.homesynapse.event.bus.DerivedWriteRateLimit;
import com.homesynapse.event.bus.QueueSaturationHealthCheck;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single-threaded scheduler driving the periodic maintenance tasks shared
 * by the composition root: token-bucket replenishment and queue-saturation
 * tick (AMD-43 §3.6.3, §3.6.4), plus any additional periodic task registered
 * post-construction via {@link #schedulePeriodic(String, Runnable, long)}.
 *
 * <h2>Scheduled tasks</h2>
 *
 * <ul>
 *   <li>{@link DerivedWriteRateLimit#refill()} every {@value #REFILL_PERIOD_MILLIS} ms
 *       — replenishes the per-subscriber token bucket (200 tokens/sec
 *       effective).</li>
 *   <li>{@link QueueSaturationHealthCheck#tick()} every {@value #TICK_PERIOD_MILLIS} ms
 *       — advances the hysteresis state machine that emits writer-queue
 *       saturation health signals.</li>
 *   <li>Any task added post-construction via
 *       {@link #schedulePeriodic(String, Runnable, long)} — M7.4c registers the
 *       {@code pending_command_ledger}'s {@code pollExpirations()} deadline sweep
 *       this way, since the ledger is constructed AFTER this scheduler (the ledger
 *       registration must follow the state projection reaching {@code LIVE}).</li>
 * </ul>
 *
 * <p>All tasks are sub-millisecond non-blocking operations under normal
 * conditions. They share a single platform thread (named {@code "hs-sched-0"})
 * because allocating more would waste the Pi 4's 4-core carrier budget.
 * {@link ScheduledExecutorService} requires platform threads — virtual
 * threads cannot drive its tick generator.</p>
 *
 * <h2>Drift tolerance</h2>
 *
 * <p>Uses {@link ScheduledExecutorService#scheduleAtFixedRate} (not
 * {@code scheduleWithFixedDelay}) so a temporarily slow tick does not push
 * subsequent ticks late. If a tick overruns its 50 ms window — the brief's
 * STOP-gate threshold is 5 ms — the next tick fires immediately and the rate
 * limiter's refill stays on schedule on average.</p>
 *
 * <h2>Lifecycle</h2>
 *
 * <p>Construction starts the two built-in scheduled tasks immediately; further
 * tasks registered via {@link #schedulePeriodic(String, Runnable, long)} begin on
 * registration. {@link #shutdown()} calls
 * {@link ScheduledExecutorService#shutdownNow()} and waits up to two seconds for
 * the executor thread to terminate, cancelling EVERY scheduled task (built-in and
 * post-construction). Idempotent — subsequent shutdowns are no-ops.</p>
 *
 * <p>Package-private — the composition root constructs and owns the
 * scheduler. No external module touches it.</p>
 *
 * @see DerivedWriteRateLimit
 * @see QueueSaturationHealthCheck
 */
final class SharedScheduler {

    /** Token bucket refill cadence — matches DerivedWriteRateLimit.REFILL_TICK_MILLIS. */
    static final long REFILL_PERIOD_MILLIS = 50L;

    /** Saturation health-check cadence. */
    static final long TICK_PERIOD_MILLIS = 1_000L;

    /** Grace period for executor termination on shutdown. */
    private static final long SHUTDOWN_GRACE_MILLIS = 2_000L;

    private static final Logger log = LoggerFactory.getLogger(SharedScheduler.class);

    private final ScheduledExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Production constructor. Wraps the rate limiter's {@code refill()} and
     * the health check's {@code tick()} as the two scheduled tasks and
     * delegates to {@link #SharedScheduler(Runnable, Runnable)}.
     *
     * @param rateLimit   the rate limiter whose {@code refill()} runs every
     *                    {@value #REFILL_PERIOD_MILLIS} ms; never {@code null}
     * @param healthCheck the saturation health check whose {@code tick()}
     *                    runs every {@value #TICK_PERIOD_MILLIS} ms; never
     *                    {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    SharedScheduler(DerivedWriteRateLimit rateLimit,
                    QueueSaturationHealthCheck healthCheck) {
        this(
                Objects.requireNonNull(rateLimit, "rateLimit must not be null")::refill,
                Objects.requireNonNull(healthCheck, "healthCheck must not be null")::tick);
    }

    /**
     * Test-friendly constructor accepting the two scheduled tasks as raw
     * {@link Runnable}s. The production overload delegates here. Tests use
     * this form because {@link DerivedWriteRateLimit} and
     * {@link QueueSaturationHealthCheck} are {@code final} and cannot be
     * mocked.
     *
     * @param refillTask runs every {@value #REFILL_PERIOD_MILLIS} ms; never
     *                   {@code null}
     * @param tickTask   runs every {@value #TICK_PERIOD_MILLIS} ms; never
     *                   {@code null}
     */
    SharedScheduler(Runnable refillTask, Runnable tickTask) {
        Objects.requireNonNull(refillTask, "refillTask must not be null");
        Objects.requireNonNull(tickTask, "tickTask must not be null");

        this.executor = Executors.newSingleThreadScheduledExecutor(daemonThreadFactory());

        executor.scheduleAtFixedRate(
                () -> safelyInvoke("refill", refillTask),
                REFILL_PERIOD_MILLIS,
                REFILL_PERIOD_MILLIS,
                TimeUnit.MILLISECONDS);

        executor.scheduleAtFixedRate(
                () -> safelyInvoke("tick", tickTask),
                TICK_PERIOD_MILLIS,
                TICK_PERIOD_MILLIS,
                TimeUnit.MILLISECONDS);
    }

    /**
     * Registers an additional periodic task to run on the shared scheduler thread
     * at a fixed cadence, starting after one full period. Used by the composition
     * root for tasks whose collaborators are constructed AFTER this scheduler — M7.4c
     * drives the {@code pending_command_ledger}'s {@code pollExpirations()} deadline
     * sweep this way (the ledger registers only after the state projection is
     * {@code LIVE}, well after this scheduler is built).
     *
     * <p>The task is wrapped in the same {@code safelyInvoke} guard as the built-in
     * tasks, so a thrown {@link RuntimeException} is logged but never cancels the
     * cadence. {@link #shutdown()} cancels this task with the rest; there is no
     * per-task unschedule (the composition root tears down the whole scheduler).</p>
     *
     * <p>Uses {@link ScheduledExecutorService#scheduleAtFixedRate} (not
     * {@code scheduleWithFixedDelay}), matching the built-in tasks, so a slow tick
     * does not drift the cadence.</p>
     *
     * @param name        a short diagnostic label for the task (used in the failure
     *                    log); never {@code null}
     * @param task        the periodic task to run; never {@code null}
     * @param periodMillis the cadence in milliseconds; must be {@code > 0}
     * @throws NullPointerException     if {@code name} or {@code task} is {@code null}
     * @throws IllegalArgumentException if {@code periodMillis <= 0}
     * @throws IllegalStateException    if the scheduler has already been shut down
     */
    void schedulePeriodic(String name, Runnable task, long periodMillis) {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(task, "task must not be null");
        if (periodMillis <= 0) {
            throw new IllegalArgumentException(
                    "periodMillis must be positive, got " + periodMillis);
        }
        if (closed.get()) {
            throw new IllegalStateException(
                    "cannot schedule '" + name + "' on a shut-down scheduler");
        }
        executor.scheduleAtFixedRate(
                () -> safelyInvoke(name, task),
                periodMillis,
                periodMillis,
                TimeUnit.MILLISECONDS);
    }

    /**
     * Shuts down the scheduler, awaiting termination for up to two seconds.
     * Idempotent — safe to call multiple times.
     */
    void shutdown() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(SHUTDOWN_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
                log.warn("Shared scheduler did not terminate within {} ms",
                        SHUTDOWN_GRACE_MILLIS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while awaiting shared scheduler termination");
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Internals
    // ──────────────────────────────────────────────────────────────────

    /**
     * Invokes the given task, logging any thrown exception at ERROR but never
     * rethrowing — {@link ScheduledExecutorService} cancels future executions
     * of a task whose run throws, which would silently disable the scheduler.
     * Catching and logging keeps the cadence alive across transient faults.
     */
    private static void safelyInvoke(String taskName, Runnable task) {
        try {
            task.run();
        } catch (RuntimeException e) {
            log.error("Shared scheduler task '{}' failed; cadence continues", taskName, e);
        }
    }

    /**
     * Returns a thread factory producing daemon platform threads named
     * {@code "hs-sched-0"}, {@code "hs-sched-1"}, etc. Daemon status means
     * the JVM can exit even if the scheduler was not explicitly shut down —
     * defence against composition-root bugs that forget to call
     * {@link #shutdown()}.
     */
    private static ThreadFactory daemonThreadFactory() {
        AtomicLong counter = new AtomicLong();
        return runnable -> {
            Thread t = new Thread(runnable, "hs-sched-" + counter.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
    }
}
