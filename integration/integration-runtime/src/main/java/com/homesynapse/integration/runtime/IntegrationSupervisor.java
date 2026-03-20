/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.runtime;

import com.homesynapse.integration.IntegrationFactory;
import com.homesynapse.platform.identity.IntegrationId;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * The central supervisory contract managing integration adapter lifecycle,
 * health state machine, restart intensity, thread allocation, and shutdown
 * (Doc 05 §8.1).
 *
 * <p>The {@code IntegrationSupervisor} is the single point of control for all
 * integration adapter lifecycles within the HomeSynapse process. It implements
 * an OTP-style one-for-one supervision strategy (Doc 05 §3.4, L4): only the
 * failed adapter is restarted; all other integrations continue operating. This
 * directly serves INV-RF-01 (integration isolation) — a misbehaving integration
 * cannot starve the event bus of CPU, leak memory until the JVM crashes, or
 * block system startup.</p>
 *
 * <h2>Supervision strategy</h2>
 *
 * <p>Restart intensity is tracked per integration. By default, 3 restarts
 * within 60 seconds (configurable via
 * {@link com.homesynapse.integration.HealthParameters#maxRestarts()} and
 * {@link com.homesynapse.integration.HealthParameters#restartWindow()})
 * escalates the integration to
 * {@link com.homesynapse.integration.HealthState#FAILED}. The supervisor
 * classifies every exception escaping the adapter boundary using
 * {@link ExceptionClassification} before deciding whether to restart or
 * transition to FAILED.</p>
 *
 * <h2>Startup and shutdown</h2>
 *
 * <p>{@link #start(List)} is asynchronous — it returns a
 * {@link CompletableFuture} that completes when all enabled integrations
 * have been started (where "started" means {@code initialize()} returned or
 * timed out, NOT that the adapter connected to its external device). A failing
 * integration is marked FAILED; the system proceeds (INV-RF-03). Startup order
 * respects the dependency graph declared via
 * {@link com.homesynapse.integration.IntegrationDescriptor#dependsOn()}.</p>
 *
 * <p>{@link #stop()} is synchronous — it blocks until all adapters have
 * stopped or timed out. Shutdown proceeds in reverse startup order
 * (dependents before dependencies). Each adapter receives a configurable
 * grace period (default 10 seconds) to complete.</p>
 *
 * <h2>Integration discovery</h2>
 *
 * <p>Per DECIDE-04, integrations are discovered from an explicitly constructed
 * factory list — no {@link java.util.ServiceLoader} or reflection. The
 * application module assembles the {@code List<IntegrationFactory>} and passes
 * it to {@link #start(List)}.</p>
 *
 * <h2>Thread safety</h2>
 *
 * <p>The supervisor is thread-safe. Multiple threads (REST API handlers,
 * lifecycle module, health monitor) may call methods concurrently. All
 * returned collections and records are immutable snapshots.</p>
 *
 * @see IntegrationFactory
 * @see com.homesynapse.integration.IntegrationAdapter
 * @see com.homesynapse.integration.IntegrationContext
 * @see IntegrationHealthRecord
 * @see com.homesynapse.integration.HealthState
 * @see com.homesynapse.integration.HealthParameters
 */
public interface IntegrationSupervisor {

    /**
     * Discovers, validates, and starts all enabled integrations from the
     * provided factory list.
     *
     * <p>Execution proceeds as follows: read each factory's descriptor,
     * validate descriptor consistency, build the dependency graph, perform
     * topological sort (Kahn's algorithm with cycle detection per AMD-14,
     * Doc 05 §3.13), allocate threads per
     * {@link com.homesynapse.integration.IoType}, construct
     * {@link com.homesynapse.integration.IntegrationContext} per adapter,
     * and start all enabled integrations concurrently respecting dependency
     * order.</p>
     *
     * <p>The returned future completes when all integrations have been
     * started — where "started" means {@code initialize()} returned or
     * timed out. Failed integrations are marked
     * {@link com.homesynapse.integration.HealthState#FAILED}; the system
     * proceeds. A failing integration never blocks startup (INV-RF-03).</p>
     *
     * @param factories the integration factory list, assembled by the
     *                  application module; never {@code null}, must not
     *                  be empty
     * @return a future that completes when all integrations have been
     *         started or have failed; never {@code null}
     */
    CompletableFuture<Void> start(List<IntegrationFactory> factories);

    /**
     * Initiates graceful shutdown of all running integrations.
     *
     * <p>Stops integrations in reverse startup order (dependents before
     * dependencies). For virtual thread adapters, the supervisor uses
     * {@code Thread.interrupt()}. For platform thread serial adapters,
     * the supervisor closes the serial port. Each adapter receives a
     * configurable grace period (default 10 seconds) to complete.
     * Abandoned adapters are logged.</p>
     *
     * <p>Produces {@code integration_stopped} events for clean shutdowns.
     * This method blocks until all adapters have stopped or timed out.</p>
     */
    void stop();

    /**
     * Manually restarts a FAILED integration.
     *
     * <p>Used by the REST API or configuration reload to recover a
     * manually-intervened integration. Resets health counters, transitions
     * through LOADING → INITIALIZING → RUNNING, and produces an
     * {@code integration_restarted} event.</p>
     *
     * @param id the integration to start; never {@code null}
     * @return a future that completes when the integration has been started
     *         or has failed; never {@code null}
     * @throws IllegalStateException if the integration is not in
     *         {@link com.homesynapse.integration.HealthState#FAILED} state
     */
    CompletableFuture<Void> startIntegration(IntegrationId id);

    /**
     * Stops a single running integration.
     *
     * <p>Used by the REST API integration management endpoints to
     * administratively stop an integration without shutting down
     * the entire supervisor.</p>
     *
     * @param id the integration to stop; never {@code null}
     * @return a future that completes when the integration has stopped
     *         or timed out; never {@code null}
     */
    CompletableFuture<Void> stopIntegration(IntegrationId id);

    /**
     * Stops and then starts a single integration.
     *
     * <p>Convenience method for the REST API. Equivalent to calling
     * {@link #stopIntegration(IntegrationId)} followed by
     * {@link #startIntegration(IntegrationId)}.</p>
     *
     * @param id the integration to restart; never {@code null}
     * @return a future that completes when the integration has been
     *         restarted or has failed; never {@code null}
     */
    CompletableFuture<Void> restartIntegration(IntegrationId id);

    /**
     * Returns the current health record snapshot for the specified integration.
     *
     * <p>The returned record is immutable and represents a point-in-time
     * snapshot of the integration's health state, scores, and sliding
     * window metrics.</p>
     *
     * @param id the integration to query; never {@code null}
     * @return the health record, or empty if the integration is not
     *         registered; never {@code null}
     */
    Optional<IntegrationHealthRecord> health(IntegrationId id);

    /**
     * Returns an unmodifiable map of all registered integrations' health
     * records.
     *
     * <p>Consumed by the REST API integration list/health endpoints
     * (Doc 09 §3.2, §7) and the Observability module's composite health
     * indicator (Doc 11 §11.3). The map is a snapshot — subsequent health
     * changes are not reflected in the returned map.</p>
     *
     * @return an unmodifiable map keyed by {@link IntegrationId};
     *         never {@code null}, may be empty
     */
    Map<IntegrationId, IntegrationHealthRecord> allHealth();

    /**
     * Returns {@code true} if the specified integration is in
     * {@link com.homesynapse.integration.HealthState#HEALTHY} or
     * {@link com.homesynapse.integration.HealthState#DEGRADED} state
     * (i.e., actively running).
     *
     * @param id the integration to query; never {@code null}
     * @return {@code true} if the integration is currently running
     */
    boolean isRunning(IntegrationId id);

    /**
     * Returns the set of all registered integration IDs.
     *
     * @return an unmodifiable set of registered integration IDs;
     *         never {@code null}, may be empty
     */
    Set<IntegrationId> registeredIntegrations();
}
