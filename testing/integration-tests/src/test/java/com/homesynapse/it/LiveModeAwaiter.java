/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.it;

import com.homesynapse.event.bus.SubscriberMode;

import org.awaitility.Awaitility;

import java.time.Duration;
import java.util.Objects;

/**
 * Awaitility-based helper for waiting on
 * {@link SubscriberMode#LIVE LIVE} mode (Research 3 REC-13, reinterpreted).
 *
 * <h2>REC-13 reinterpretation</h2>
 *
 * <p>The original Research 3 framing suggested {@code bus.isLive()}. There
 * is no {@code isLive()} method on {@code EventBus} — liveness is
 * per-subscriber (the bus may have multiple subscribers in different lifecycle
 * modes), and the M3.7-relevant signal is the projection subscriber's mode.
 * {@code HomeSynapseCore.mode()} (its {@code ReadinessSource} impl) exposes
 * exactly that, by reading the projection subscriber's mode from
 * {@code EventBus.subscribers()} (M3.7 fix round 1). This helper wraps that
 * accessor with a short polling loop.</p>
 *
 * <p>This is the M3.7 closure of REC-13 — the {@code EventBus} contract
 * stays per-subscriber as designed (no {@code isLive()} method added).</p>
 *
 * @see HomeSynapseE2eHarness#mode()
 */
final class LiveModeAwaiter {

    /**
     * Default timeout for the {@link #awaitLive(HomeSynapseE2eHarness)}
     * overload — 5 seconds (REC-13 recommendation). Long enough for the
     * COLD → REPLAY → TRANSITION → LIVE sequence to complete on Pi-class
     * hardware, short enough that a bug surfaces as a failing test rather
     * than a 30-second wall-clock pause.
     */
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    /** Polling cadence — frequent enough that the test exits promptly. */
    private static final Duration POLL_INTERVAL = Duration.ofMillis(50);

    private LiveModeAwaiter() {
        // utility class
    }

    /**
     * Blocks until the harness's projection subscriber reaches
     * {@link SubscriberMode#LIVE LIVE} or the supplied timeout expires.
     *
     * @param harness the E2E harness; never {@code null}
     * @param timeout maximum wait; never {@code null}
     * @throws org.awaitility.core.ConditionTimeoutException if the projection
     *         has not reached LIVE within {@code timeout}
     */
    static void awaitLive(HomeSynapseE2eHarness harness, Duration timeout) {
        Objects.requireNonNull(harness, "harness");
        Objects.requireNonNull(timeout, "timeout");
        Awaitility.await()
                .atMost(timeout)
                .pollInterval(POLL_INTERVAL)
                .until(() -> harness.mode() == SubscriberMode.LIVE);
    }

    /**
     * Default-timeout convenience overload — uses {@link #DEFAULT_TIMEOUT}
     * (5 seconds).
     *
     * @param harness the E2E harness; never {@code null}
     */
    static void awaitLive(HomeSynapseE2eHarness harness) {
        awaitLive(harness, DEFAULT_TIMEOUT);
    }
}
