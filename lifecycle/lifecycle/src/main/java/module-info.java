/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

/**
 * Process-level lifecycle orchestration for HomeSynapse Core.
 *
 * <p>This module owns the ordered initialization of all subsystems from cold start,
 * the runtime health and watchdog protocol that feeds systemd's liveness detection,
 * and the graceful shutdown sequence that preserves data integrity. The primary entry
 * point is {@link com.homesynapse.lifecycle.SystemLifecycleManager#start()}, called
 * from main(). The lifecycle module publishes the initialization order that all other
 * subsystems depend on, provides platform abstraction interfaces for systemd and
 * directory conventions, and tracks system-wide health state via {@link
 * com.homesynapse.lifecycle.SystemHealthSnapshot}.</p>
 *
 * @see com.homesynapse.lifecycle
 */
module com.homesynapse.lifecycle {
    requires transitive com.homesynapse.observability;
    requires transitive com.homesynapse.event;
    requires transitive com.homesynapse.platform;

    exports com.homesynapse.lifecycle;
}
