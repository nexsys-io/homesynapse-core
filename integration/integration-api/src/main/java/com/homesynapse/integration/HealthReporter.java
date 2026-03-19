/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration;

import java.time.Instant;

/**
 * Adapter-to-supervisor health signal channel (Doc 05 §8.1, §8.5).
 *
 * <p>{@code HealthReporter} is the integration adapter's interface for
 * communicating health status to the supervisor's health state machine.
 * The supervisor uses these signals — combined with its own monitoring
 * metrics — to evaluate the adapter's health and trigger state transitions
 * in the four-state health model ({@link HealthState}).</p>
 *
 * <p>There are three categories of health signals, each with distinct
 * semantics:</p>
 * <ul>
 *   <li><strong>Heartbeat</strong> ({@link #reportHeartbeat()}) — Liveness
 *       signal. The adapter is executing and responsive. The supervisor uses
 *       heartbeat absence to detect hangs or deadlocks.</li>
 *   <li><strong>Keepalive</strong> ({@link #reportKeepalive(Instant)}) —
 *       Protocol-level connectivity signal. The adapter has successfully
 *       communicated with its external device or service. More informative
 *       than a heartbeat because it confirms end-to-end connectivity.</li>
 *   <li><strong>Health transition</strong>
 *       ({@link #reportHealthTransition(HealthState, String)}) —
 *       Self-assessed state suggestion. The adapter detects a condition that
 *       external monitoring cannot observe (e.g., semantic errors in valid
 *       responses). The supervisor may accept or override the suggestion.</li>
 * </ul>
 *
 * <p><strong>Thread safety:</strong> All methods are thread-safe and may be
 * called from any thread within the adapter's thread group — including the
 * main processing thread, internal virtual threads, and scheduled task
 * callbacks.</p>
 *
 * @see HealthState
 * @see HealthParameters
 * @see IntegrationAdapter
 */
public interface HealthReporter {

    /**
     * Updates the adapter's heartbeat timestamp, signaling that the adapter
     * is alive and executing.
     *
     * <p>Call this on every iteration of the adapter's main loop, including
     * iterations where no data arrived. The supervisor compares the heartbeat
     * timestamp against {@link HealthParameters#heartbeatTimeout()} to detect
     * unresponsive adapters.</p>
     */
    void reportHeartbeat();

    /**
     * Reports the timestamp of the last successful protocol-level keepalive.
     *
     * <p>Unlike {@link #reportHeartbeat()} (which confirms the adapter process
     * is running), this method confirms that the adapter has successfully
     * communicated with its external device or service at the protocol level.
     * For example, a Zigbee adapter reports a keepalive when it receives a
     * valid ZCL response from the coordinator.</p>
     *
     * @param lastSuccess the timestamp of the last successful protocol
     *                    communication; never {@code null}
     */
    void reportKeepalive(Instant lastSuccess);

    /**
     * Registers an error with the supervisor's sliding window for health
     * score calculation.
     *
     * <p>The adapter has already handled the error (logged it, attempted
     * recovery). This call records it for health tracking. The supervisor
     * classifies the error and updates the weighted health score. If the
     * error rate exceeds the configured threshold, the supervisor may
     * transition the adapter to {@link HealthState#DEGRADED}.</p>
     *
     * @param error the handled error to record; never {@code null}
     */
    void reportError(Throwable error);

    /**
     * Suggests a health state transition with a human-readable reason.
     *
     * <p>The adapter suggests a state, but the supervisor may accept or
     * override the suggestion based on its own metrics. This allows adapters
     * to signal conditions that external monitoring cannot detect — for
     * example, receiving valid HTTP 200 responses that contain semantic
     * errors indicating the upstream service is degraded.</p>
     *
     * <p>The reason string should use Register C voice (direct, neutral)
     * because it surfaces in lifecycle events, dashboards, and logs.</p>
     *
     * @param suggestedState the health state the adapter believes it should
     *                       be in; never {@code null}
     * @param reason         human-readable explanation for the suggested
     *                       transition; never {@code null}
     */
    void reportHealthTransition(HealthState suggestedState, String reason);
}
