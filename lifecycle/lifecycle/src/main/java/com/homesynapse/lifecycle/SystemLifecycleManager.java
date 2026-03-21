/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.lifecycle;

import java.util.Map;

/**
 * Top-level orchestrator for the HomeSynapse process lifecycle.
 *
 * <p>Sequences the six initialization phases ({@link LifecyclePhase#BOOTSTRAP}
 * through {@link LifecyclePhase#INTEGRATIONS}), runs the runtime health loop
 * (Doc 12 §3.10 after INTEGRATIONS completes), and executes the shutdown sequence
 * (Doc 12 §3.9 when SIGTERM arrives). This is the primary interface — the
 * {@code main()} method calls {@link #start()} and registers a JVM shutdown hook
 * that calls {@link #shutdown(String)}.</p>
 *
 * <p>Thread-safety: {@link #currentPhase()}, {@link #healthSnapshot()}, and
 * {@link #subsystemStates()} are callable from any thread at any time.
 * {@link #start()} is called once from {@code main()}.
 * {@link #shutdown(String)} is safe to call from the shutdown hook and is
 * synchronized internally — concurrent calls are serialized.</p>
 *
 * @see HealthReporter
 * @see PlatformPaths
 * @see SubsystemState
 * @see SystemHealthSnapshot
 */
public interface SystemLifecycleManager {

    /**
     * Executes the full startup sequence (Phases 0–6) synchronously.
     *
     * <p>Blocks until initialization completes or a fatal error occurs. If a
     * fatal failure occurs, calls {@link #shutdown(String)} for any
     * already-initialized subsystems and throws an exception (or calls
     * {@code System.exit(1)} with diagnostic logging before throwing).</p>
     *
     * <p>Called from {@code main()} during application startup.</p>
     *
     * @throws Exception if a fatal failure occurs during initialization;
     *         Phase 3 implementation will define specific exception types
     *         or wrap all exceptions in a {@code StartupFailedException}
     */
    void start() throws Exception;

    /**
     * Executes the shutdown sequence in reverse initialization order.
     *
     * <p>Safe to call from the JVM shutdown hook, from {@link #start()} on
     * fatal failure, or from an explicit admin API call. Concurrent calls are
     * serialized — the first call executes shutdown; subsequent calls wait for
     * completion.</p>
     *
     * @param reason human-readable reason for shutdown (e.g., "SIGTERM",
     *        "fatal error: persistence layer failed"); must not be {@code null}
     * @throws Exception during shutdown; Phase 3 may refine exception types
     */
    void shutdown(String reason) throws Exception;

    /**
     * Returns the current lifecycle phase.
     *
     * <p>Returns {@link LifecyclePhase#STOPPED} after shutdown completes.
     * Called by the REST API {@code /api/v1/system/health} endpoint and
     * WebSocket health streaming.</p>
     *
     * @return the current phase, never {@code null}
     */
    LifecyclePhase currentPhase();

    /**
     * Returns a point-in-time snapshot of the system's health state.
     *
     * <p>Captures subsystem states, aggregated health, uptime, entity count,
     * and other metrics. Called by the REST health endpoint and WebSocket
     * consumers.</p>
     *
     * @return the current health snapshot, never {@code null}
     * @see SystemHealthSnapshot
     */
    SystemHealthSnapshot healthSnapshot();

    /**
     * Returns the per-subsystem state map keyed by subsystem name.
     *
     * <p>This is the detailed breakdown of individual subsystem states.
     * {@link #healthSnapshot()} includes this data plus additional system-wide
     * metrics.</p>
     *
     * @return unmodifiable map of subsystem names to their states,
     *         never {@code null}
     */
    Map<String, SubsystemState> subsystemStates();
}
