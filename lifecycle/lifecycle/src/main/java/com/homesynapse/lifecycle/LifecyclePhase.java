/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

package com.homesynapse.lifecycle;

/**
 * The ten sequential lifecycle states of a HomeSynapse process.
 *
 * <p>{@code BOOTSTRAP} through {@code INTEGRATIONS} are initialization phases
 * executing sequentially in documented order (C12-01). A subsystem that initializes
 * in phase N depends only on subsystems from phases 0 through N-1. {@code RUNNING}
 * is the steady-state phase where the health loop (Doc 12 §3.10) executes.
 * {@code SHUTTING_DOWN} is the graceful shutdown sequence (Doc 12 §3.9), reversing
 * initialization order with grace periods. {@code STOPPED} is the terminal state
 * after all resources are released.</p>
 *
 * <p>The process moves through states strictly in order — no backward transitions
 * are permitted.</p>
 *
 * <p>Thread-safe: enum values are immutable constants.</p>
 *
 * @see SystemLifecycleManager
 */
public enum LifecyclePhase {

    /** Phase 0: platform detection, logging, JFR, HealthReporter selection. */
    BOOTSTRAP,

    /** Phase 1: configuration loading and validation. */
    FOUNDATION,

    /** Phase 2: persistence layer, event bus initialization. */
    DATA_INFRASTRUCTURE,

    /** Phase 3: device model, state store, automation engine. */
    CORE_DOMAIN,

    /** Phase 4: health aggregation, metrics infrastructure. */
    OBSERVABILITY,

    /** Phase 5: REST API, WebSocket API — READY=1 notification sent at end. */
    EXTERNAL_INTERFACES,

    /** Phase 6: integration adapter discovery and startup — occurs after READY. */
    INTEGRATIONS,

    /** Steady state: health loop active, all subsystems operational. */
    RUNNING,

    /** Shutdown sequence in progress — reverse initialization order. */
    SHUTTING_DOWN,

    /** All resources released, process exiting. */
    STOPPED
}
