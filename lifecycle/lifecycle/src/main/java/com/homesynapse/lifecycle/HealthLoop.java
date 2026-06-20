/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.lifecycle;

import com.homesynapse.platform.HealthReporter;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The runtime health loop (Doc 12 §3.10). After the engine reaches
 * {@link LifecyclePhase#RUNNING}, this loop runs on a dedicated virtual thread
 * and, every {@code WatchdogSec / 2} seconds, pets the watchdog and reports a
 * one-line status to the platform supervisor.
 *
 * <p>The system does <strong>not</strong> self-terminate on UNHEALTHY — the
 * platform watchdog handles hangs (a missed {@link #tick()} for a full
 * {@code WatchdogSec} causes the supervisor to kill/restart the process). The
 * loop body restarts on an unhandled exception so a transient fault cannot
 * silently stop the watchdog.</p>
 *
 * <h2>Testability</h2>
 *
 * <p>{@link #tick()} is package-visible and side-effect-only so a test can drive
 * one iteration directly under an injected {@link Clock} and a recording
 * {@link HealthReporter} — no real sleeping, no scheduler. {@link #start()}
 * owns the virtual-thread scheduling that production uses.</p>
 *
 * <h2>Concurrency (LTD-11)</h2>
 *
 * <p>Lifecycle transitions are guarded by a {@link ReentrantLock} (never
 * {@code synchronized}). The body runs on a single virtual thread.</p>
 */
final class HealthLoop {

    private static final Logger LOG = LoggerFactory.getLogger(HealthLoop.class);

    private final HealthReporter reporter;
    private final Clock clock;
    private final Duration period;
    private final Supplier<String> statusSupplier;

    private final ReentrantLock lifecycleLock = new ReentrantLock();
    private volatile boolean running;
    private Thread loopThread;

    /**
     * @param reporter       the platform health reporter (watchdog channel);
     *                       never {@code null}
     * @param clock          injected clock (NO_DIRECT_TIME_ACCESS); never
     *                       {@code null}. Currently used only to satisfy the
     *                       injected-time discipline; reserved for future
     *                       unhealthy-duration tracking (Doc 12 §3.10 step 5).
     * @param period         the watchdog notify period ({@code WatchdogSec / 2});
     *                       never {@code null}
     * @param statusSupplier supplies the one-line status string each period;
     *                       never {@code null}
     */
    HealthLoop(HealthReporter reporter, Clock clock, Duration period,
               Supplier<String> statusSupplier) {
        this.reporter = Objects.requireNonNull(reporter, "reporter");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.period = Objects.requireNonNull(period, "period");
        this.statusSupplier = Objects.requireNonNull(statusSupplier, "statusSupplier");
    }

    /**
     * Executes one health-loop iteration: pet the watchdog ({@code WATCHDOG=1})
     * and report the current status line. Side-effect-only and safe to call
     * directly from tests.
     */
    void tick() {
        reporter.reportWatchdog();
        reporter.reportStatus(statusSupplier.get());
    }

    /** Starts the loop on a dedicated daemon virtual thread. Idempotent. */
    void start() {
        lifecycleLock.lock();
        try {
            if (running) {
                return;
            }
            running = true;
            loopThread = Thread.ofVirtual().name("hs-health-0").start(this::runLoop);
        } finally {
            lifecycleLock.unlock();
        }
    }

    private void runLoop() {
        while (running) {
            try {
                tick();
            } catch (RuntimeException e) {
                // Doc 12 §3.10: the loop restarts on an unhandled exception so a
                // transient fault cannot silently stop the watchdog.
                LOG.error("health-loop tick failed; continuing", e);
            }
            try {
                Thread.sleep(period);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Stops the loop and interrupts the loop thread. Idempotent. */
    void stop() {
        lifecycleLock.lock();
        try {
            running = false;
            if (loopThread != null) {
                loopThread.interrupt();
            }
        } finally {
            lifecycleLock.unlock();
        }
    }
}
