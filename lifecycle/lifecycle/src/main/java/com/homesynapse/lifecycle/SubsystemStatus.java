/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

package com.homesynapse.lifecycle;

/**
 * The six possible states of a single subsystem within the overall lifecycle.
 *
 * <p>A subsystem transitions: {@code NOT_STARTED} → {@code INITIALIZING} (on start)
 * → {@code RUNNING} (on success) or {@code FAILED} (on error). From {@code RUNNING}
 * or {@code FAILED}, the subsystem may transition to {@code STOPPING} → {@code STOPPED}
 * when the shutdown sequence executes. {@code NOT_STARTED} subsystems are skipped
 * during shutdown — they never had resources to release.</p>
 *
 * <p>Thread-safe: enum values are immutable constants.</p>
 *
 * @see SubsystemState
 * @see SystemLifecycleManager
 */
public enum SubsystemStatus {

    /** Subsystem has not begun initialization. */
    NOT_STARTED,

    /** {@code initialize()} in progress. */
    INITIALIZING,

    /** Successfully initialized and operating. */
    RUNNING,

    /** Initialization failed or runtime failure detected. */
    FAILED,

    /** Shutdown in progress. */
    STOPPING,

    /** Shutdown complete, all resources released. */
    STOPPED
}
