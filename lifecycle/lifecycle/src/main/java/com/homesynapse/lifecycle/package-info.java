/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

/**
 * Process-level lifecycle orchestration for HomeSynapse Core.
 *
 * <p>This package provides five capabilities:</p>
 *
 * <ul>
 *   <li><strong>Lifecycle State Machine</strong> — The system moves through ten
 *       sequential phases: {@link LifecyclePhase#BOOTSTRAP} (platform initialization),
 *       {@link LifecyclePhase#FOUNDATION} (configuration), {@link
 *       LifecyclePhase#DATA_INFRASTRUCTURE} (persistence and event bus), {@link
 *       LifecyclePhase#CORE_DOMAIN} (device model, state store, automation), {@link
 *       LifecyclePhase#OBSERVABILITY} (health aggregation), {@link
 *       LifecyclePhase#EXTERNAL_INTERFACES} (REST and WebSocket APIs), {@link
 *       LifecyclePhase#INTEGRATIONS} (integration adapter startup), {@link
 *       LifecyclePhase#RUNNING} (steady state), {@link
 *       LifecyclePhase#SHUTTING_DOWN} (graceful shutdown), and {@link
 *       LifecyclePhase#STOPPED} (terminated).</li>
 *   <li><strong>Initialization Orchestration</strong> — {@link
 *       SystemLifecycleManager} sequences all subsystems through their initialization
 *       phases in documented order. No subsystem may depend on a subsystem from a
 *       later phase. Fatal failures (Configuration System, Persistence Layer, Event
 *       Bus, Device Model, State Store, Automation Engine, REST API) exit the process
 *       with diagnostic messages. Non-fatal failures (Observability, WebSocket API,
 *       integration adapters) degrade the system health to DEGRADED and continue.</li>
 *   <li><strong>Shutdown Sequencing</strong> — Reverse initialization order with
 *       grace periods. The {@link com.homesynapse.platform.HealthReporter} interface
 *       communicates shutdown progress to systemd. Total shutdown budget: 30 seconds
 *       (half of systemd's {@code TimeoutStopSec=90}).</li>
 *   <li><strong>Health Loop and Watchdog</strong> — After RUNNING state, the health
 *       loop polls all {@link
 *       com.homesynapse.observability.HealthContributor} implementations every 30
 *       seconds, computes aggregated health via the three-tier model, and feeds
 *       systemd's watchdog via {@link
 *       com.homesynapse.platform.HealthReporter#reportWatchdog()}. Missing watchdog
 *       calls trigger process restart.</li>
 *   <li><strong>Platform Abstraction</strong> — {@link
 *       com.homesynapse.platform.HealthReporter} abstracts lifecycle notifications
 *       (systemd {@code sd_notify} messages). {@link com.homesynapse.platform.PlatformPaths}
 *       abstracts directory conventions (FHS on Linux). Both are resolved once during
 *       BOOTSTRAP and cached for the lifetime of the process.</li>
 * </ul>
 *
 * <p>Design authority: Doc 12 — Startup, Lifecycle &amp; Shutdown (Locked).</p>
 *
 * @see SystemLifecycleManager
 * @see com.homesynapse.platform.HealthReporter
 * @see com.homesynapse.platform.PlatformPaths
 */
package com.homesynapse.lifecycle;
