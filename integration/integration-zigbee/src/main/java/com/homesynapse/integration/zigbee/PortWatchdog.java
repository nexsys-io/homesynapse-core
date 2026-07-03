/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Port-death detection and reopen scheduling with capped, clock-injected backoff
 * (Doc 08 §3.3; W4/W5; charter row "isOpen() lies").
 *
 * <p>Unhealth is established by SIGNALS only — the disconnect listener (where
 * available), a read-error, or lost ASH-liveness (missed keepalives). This class never
 * consults {@code isOpen()}: a yanked USB device can keep reporting open while reads
 * fail, so {@code isOpen()} is structurally excluded as a health source (W5).
 *
 * <p>Reopen targets the STABLE identity (by-id path / VID:PID via
 * {@link PortLocator#reopenTarget(PortIdentity)}), never the possibly-renumbered
 * device node — the injected {@link ReopenAction} composes that at M9.4 wiring.
 *
 * <p>Backoff: first attempt on the next tick, then 1 s doubling to a 30 s cap —
 * chosen constants (no §3.3 numeric), deliberately faster than the supervisor's
 * adapter-restart {@code BackoffParameters} (a port reopen is cheap and local).
 * Repeat failure signals while already unhealthy do NOT reset the schedule.
 *
 * <p>Not thread-safe: driven from the adapter's single supervision context; signals
 * and ticks are serialized by the caller (M9.4 scheduler).
 *
 * @see PortLocator
 * @see SerialByteChannel
 */
final class PortWatchdog {

    /** Delay after the first failed reopen attempt (chosen constant). */
    static final long INITIAL_BACKOFF_MILLIS = 1000;
    /** Backoff ceiling (chosen constant). */
    static final long BACKOFF_CAP_MILLIS = 30_000;

    private static final Logger log = LoggerFactory.getLogger(PortWatchdog.class);

    /**
     * The reopen strategy — composed from locator + transport at M9.4 wiring.
     *
     * <p>Contract for the M9.4 composition: resolve the stable identity via
     * {@code PortLocator.reopenTarget(...)}, close and reopen the transport (fresh
     * ASH handshake), then call {@code EzspCoordinatorProtocol.resetSession()}
     * followed by {@code startSession()} — UG100 requires {@code version} to be
     * the first command after an NCP reset, and a reopened transport decodes only
     * legacy frames until the session renegotiates.
     */
    @FunctionalInterface
    interface ReopenAction {

        /**
         * Attempts one reopen against the stable identity.
         *
         * @return {@code true} if the port is open and the session is live again
         */
        boolean attemptReopen();
    }

    private final Clock clock;
    private final ReopenAction reopenAction;

    private boolean healthy = true;
    private int failedAttempts;
    private Instant nextAttemptAt;

    /**
     * Creates the watchdog. Performs no I/O (INV-RF-03).
     *
     * @param clock the time source, never {@code null}
     * @param reopenAction the reopen strategy, never {@code null}
     */
    PortWatchdog(Clock clock, ReopenAction reopenAction) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.reopenAction = Objects.requireNonNull(reopenAction, "reopenAction");
    }

    /** Signal: the platform disconnect listener fired (unplug detected natively). */
    void onDisconnectSignal() {
        markUnhealthy("disconnect-listener");
    }

    /** Signal: a serial read/write returned an error. */
    void onReadError() {
        markUnhealthy("read-error");
    }

    /** Signal: ASH-liveness lost (missed keepalives / session FAILED). */
    void onAshLivenessLost() {
        markUnhealthy("ash-liveness");
    }

    /**
     * Drives the reopen schedule. When unhealthy and the backoff delay has elapsed,
     * attempts one reopen; success restores health and resets the backoff.
     */
    void tick() {
        if (healthy) {
            return;
        }
        Instant now = clock.instant();
        if (now.isBefore(nextAttemptAt)) {
            return;
        }
        if (reopenAction.attemptReopen()) {
            int priorFailures = failedAttempts;
            healthy = true;
            failedAttempts = 0;
            log.info("zigbee.port_reopened: recovery succeeded after {} failed "
                    + "attempts", priorFailures);
            return;
        }
        failedAttempts++;
        long delay = Math.min(
                INITIAL_BACKOFF_MILLIS << Math.min(failedAttempts - 1, 30),
                BACKOFF_CAP_MILLIS);
        nextAttemptAt = now.plusMillis(delay);
        log.warn("zigbee.port_reopen_failed: attempt {} failed; next attempt in "
                + "{} ms", failedAttempts, delay);
    }

    /** Returns whether the port is currently believed healthy. */
    boolean isHealthy() {
        return healthy;
    }

    /** Returns the number of consecutive failed reopen attempts. */
    int failedAttempts() {
        return failedAttempts;
    }

    private void markUnhealthy(String cause) {
        if (!healthy) {
            // Repeat signals never reset an in-progress backoff schedule.
            return;
        }
        healthy = false;
        failedAttempts = 0;
        nextAttemptAt = clock.instant();
        log.warn("zigbee.port_unhealthy: cause={}; reopen scheduling started", cause);
    }
}
