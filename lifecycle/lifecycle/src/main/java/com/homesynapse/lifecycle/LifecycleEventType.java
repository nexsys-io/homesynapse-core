/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

package com.homesynapse.lifecycle;

/**
 * Canonical registry of lifecycle event type string constants.
 *
 * <p>All constants are in the {@code system.*} namespace as defined in Doc 12 §4.4.
 * These constants are used by the {@link SystemLifecycleManager} implementation when
 * publishing lifecycle events to the event bus via {@code EventPublisher}.</p>
 *
 * <p>Thread-safe: this class has no instance state.</p>
 *
 * @see SystemLifecycleManager
 */
public final class LifecycleEventType {

    private LifecycleEventType() {
        // Utility class — no instantiation.
    }

    /** Published when the startup sequence begins (Phase 0 entry). */
    public static final String SYSTEM_STARTING = "system.starting";

    /** Published when a subsystem completes initialization successfully. */
    public static final String SYSTEM_SUBSYSTEM_INITIALIZED = "system.subsystem_initialized";

    /** Published when a subsystem fails initialization. */
    public static final String SYSTEM_SUBSYSTEM_FAILED = "system.subsystem_failed";

    /** Published when all phases complete and the system enters RUNNING state. */
    public static final String SYSTEM_READY = "system.ready";

    /** Published when aggregated system health changes (HEALTHY, DEGRADED, UNHEALTHY). */
    public static final String SYSTEM_HEALTH_CHANGED = "system.health_changed";

    /** Published when the shutdown sequence begins. */
    public static final String SYSTEM_STOPPING = "system.stopping";

    /** Published when the shutdown sequence completes and the process is exiting. */
    public static final String SYSTEM_STOPPED = "system.stopped";
}
