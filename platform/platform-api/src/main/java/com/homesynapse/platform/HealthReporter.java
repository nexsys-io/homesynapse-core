/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.platform;

/**
 * Abstracts platform-specific lifecycle health reporting, allowing HomeSynapse
 * Core to communicate process state to the platform supervisor regardless of
 * deployment tier.
 *
 * <p>The implementation is selected during Phase 0 based on environment detection:
 * {@code SystemdHealthReporter} if the {@code $NOTIFY_SOCKET} environment variable
 * is set, {@code NoOpHealthReporter} otherwise. On non-systemd platforms (macOS,
 * Docker without {@code sd_notify}, development), all methods are no-ops.
 *
 * <p><strong>Watchdog contract (C12-03):</strong> {@link #reportWatchdog()} must be
 * called at least every {@code WatchdogSec/2} seconds (default 30 s) once
 * {@link #reportReady()} has been invoked. Missing calls for longer than
 * {@code WatchdogSec} cause the platform supervisor to kill and restart the
 * process.
 *
 * <p>Thread-safe: the health loop runs on a dedicated virtual thread;
 * {@link #reportStatus(String)} may be called from any thread during lifecycle
 * transitions.
 *
 * <p>This interface defines the <em>reporting</em> side only — it does not define
 * health <em>assessment</em> (that responsibility belongs to {@code HealthContributor}
 * from Doc 11 §8.1).
 *
 * @see PlatformPaths
 * @see <a href="doc12-section-8.2">Doc 12 §8.2 — HealthReporter Interface</a>
 * @see <a href="doc12-section-7.1">Doc 12 §7.1 — Portability Architecture</a>
 */
public interface HealthReporter {

    /**
     * Reports that the system is fully started and ready to accept connections.
     *
     * <p>Called exactly once, at the end of Phase 5, after APIs are accepting
     * connections. On Tier 1, sends {@code READY=1} via {@code sd_notify}. After
     * this call, the platform supervisor considers the service fully started and
     * begins enforcing the watchdog interval.
     *
     * @see PlatformPaths
     */
    void reportReady();

    /**
     * Reports a watchdog heartbeat to the platform supervisor.
     *
     * <p>Called periodically from the health loop (every {@code WatchdogSec/2}
     * seconds, default 30 s). On Tier 1, sends {@code WATCHDOG=1} via
     * {@code sd_notify}. Missing calls for longer than {@code WatchdogSec}
     * trigger a process restart (C12-03).
     *
     * @see PlatformPaths
     */
    void reportWatchdog();

    /**
     * Reports that the system is beginning its shutdown sequence.
     *
     * <p>Called once at the start of the shutdown sequence. On Tier 1, sends
     * {@code STOPPING=1} via {@code sd_notify}. The platform supervisor begins
     * its shutdown timeout.
     *
     * @see PlatformPaths
     */
    void reportStopping();

    /**
     * Reports a human-readable status string to the platform supervisor.
     *
     * <p>Called at lifecycle transitions (for example, entering a new phase).
     * On Tier 1, sends {@code STATUS=<message>} via {@code sd_notify}, visible
     * in {@code systemctl status homesynapse}.
     *
     * @param message a non-null human-readable status description
     * @see PlatformPaths
     */
    void reportStatus(String message);
}
