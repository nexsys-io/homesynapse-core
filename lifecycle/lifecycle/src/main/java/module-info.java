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

    // M3.6d-a: composition-root prerequisites — HomeSynapseConfig bundles
    // PersistenceConfig (from persistence) and EventBusConfig (from event-bus);
    // SharedScheduler drives DerivedWriteRateLimit.refill() and
    // QueueSaturationHealthCheck.tick() (both from event-bus);
    // ThrowingStateQueryService implements StateQueryService (from state-store).
    // All three are surfaced through the lifecycle module's exported API
    // (HomeSynapseConfig is public; SharedScheduler and ThrowingStateQueryService
    // are package-private but interact with public types from these modules),
    // so the requires are transitive per LD#10.
    requires transitive com.homesynapse.persistence;
    requires transitive com.homesynapse.event.bus;
    requires transitive com.homesynapse.state;

    // M3.6d-a build-fix: SharedScheduler uses SLF4J internally for ERROR
    // logging from safelyInvoke(). Non-transitive (LTD-15 / DECIDE-01) —
    // SLF4J is an implementation concern; no SLF4J types appear in the
    // lifecycle module's exported API. Mirrors the pattern in
    // core/persistence/module-info.java (M2.2) and
    // core/state-store/module-info.java (M3.5a).
    requires org.slf4j;

    exports com.homesynapse.lifecycle;
}
