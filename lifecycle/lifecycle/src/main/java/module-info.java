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

    // M3.6d-b: HomeSynapseCore aggregates IntegrationEvents.LIFECYCLE_EVENT_CLASSES
    // with EventTypes.CORE_PRODUCTION_EVENT_CLASSES at startup to feed the event
    // type registry. M9.1 PROMOTED this to transitive: the canonical 7-arg
    // HomeSynapseCore constructor takes List<IntegrationFactory> — an
    // integration-api type on a PUBLIC ctor of an EXPORTED class — so a plain
    // requires would trip -Xlint:exports (-Werror). Paired with api(...) in
    // build.gradle.kts (the lockstep rule).
    requires transitive com.homesynapse.integration;

    // M9.1: the composition root constructs IntegrationSupervisorAssembly and
    // holds the supervisor. Referenced only INSIDE HomeSynapseCore + the
    // package-private accessor (NOT exported API) -> plain requires,
    // implementation(...) in Gradle (the AB-3 config/device/automation pattern).
    requires com.homesynapse.integration.runtime;

    // M3.6e.1: HomeSynapseCore registers ReadinessFilter (from rest-api) as a
    // Javalin before("/api/*") gate. Non-transitive — the type is referenced
    // only inside the composition root.
    requires com.homesynapse.api.rest;

    // AB-3: the composition root assembles ConfigurationService (config),
    // the InMemory* device registries (device-model), and the automation_engine
    // subscriber chain (automation). All three are referenced only INSIDE
    // HomeSynapseCore/Main composition internals — no config/device/automation
    // type appears on the lifecycle module's exported API — so each is a plain
    // (non-transitive) requires ⇔ implementation(...) in build.gradle.kts.
    // All are gate-allowed (:lifecycle:.* -> :config:.* and -> :core:.*).
    requires com.homesynapse.config;
    requires com.homesynapse.device;
    requires com.homesynapse.automation;

    // AB-3: HomeSynapseCore selects the platform HealthReporter implementation
    // (SystemdHealthReporter when $NOTIFY_SOCKET is set, else NoOpHealthReporter).
    // The HealthReporter interface is in platform-api (already required transitive);
    // the implementations live in platform-systemd. Non-transitive — the impls are
    // referenced only inside the composition root. Allowed (:lifecycle:.* -> :platform:.*).
    requires com.homesynapse.platform.systemd;

    // M3.6e.1: HomeSynapseCore constructs and owns the embedded Javalin
    // server, tuning the underlying Jetty thread pool. Non-transitive —
    // Javalin and Jetty types do not appear in the lifecycle module's
    // exported API.
    requires io.javalin;
    requires org.eclipse.jetty.util;

    // M3.6d-a build-fix: SharedScheduler uses SLF4J internally for ERROR
    // logging from safelyInvoke(). Non-transitive (LTD-15 / DECIDE-01) —
    // SLF4J is an implementation concern; no SLF4J types appear in the
    // lifecycle module's exported API. Mirrors the pattern in
    // core/persistence/module-info.java (M2.2) and
    // core/state-store/module-info.java (M3.5a).
    requires org.slf4j;

    exports com.homesynapse.lifecycle;
}
