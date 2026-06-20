/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

/**
 * Automation engine module: trigger-condition-action rules, cascade governor,
 * command dispatch, and pending command tracking.
 *
 * <p>This module defines the public API contracts for the HomeSynapse automation
 * subsystem. It exports sealed type hierarchies (triggers, conditions, actions,
 * selectors), data records (automation definitions, run contexts, pending commands),
 * and service interfaces consumed by the REST API, WebSocket API, Observability,
 * and Lifecycle modules.</p>
 */
module com.homesynapse.automation {
    requires transitive com.homesynapse.platform;
    requires transitive com.homesynapse.event;
    requires transitive com.homesynapse.device;
    requires transitive com.homesynapse.state;

    // M4.0b-4a: PendingCommand's javadoc references com.homesynapse.value
    // .AttributeValue (via {@link Expectation#evaluate}); declared non-transitive
    // (value is not on automation's public API). The type is also reachable
    // transitively through `requires transitive com.homesynapse.device`; the edge
    // is declared explicitly at its use site per the relocation design note.
    requires com.homesynapse.value;

    // M7.1: the engine's automation_engine bus subscriber imports event-bus
    // (a legal core->core edge). The config edge that FIX-07 proposed re-adding
    // is NOT taken: core->config is forbidden by assertAllowedModuleDependencies
    // at EVERY scope (a stricter gate than the exported-API §authoring check).
    // The automations.yaml schema registration + definition-document load ride
    // the composition root (lifecycle/app), which may depend on both core and
    // config; the loader itself consumes an already-parsed Map (no config edge).
    //
    // AB-3: bumped plain -> transitive. The public AutomationEngineAssembly seam
    // (composition-root access to the automation_engine subscriber) returns the
    // event-bus Subscriber interface, putting it on this module's exported API.
    // The -Xlint:exports authoring rule (api <-> requires transitive) then
    // requires transitive here (lockstep: build.gradle.kts uses api(...)). The
    // concrete AutomationEngineSubscriber stays package-private; only the
    // Subscriber interface is exposed (same pattern as lifecycle's HomeSynapseCore
    // accessors returning event-bus/event/state types via requires transitive).
    requires transitive com.homesynapse.event.bus;

    // M7.1: the Phase-3 implementations (registry, evaluators, loader,
    // duration timers) log via SLF4J. Non-transitive — no SLF4J type appears
    // on the exported API (LTD-15 / DECIDE-01), mirroring state-store/persistence.
    requires org.slf4j;

    exports com.homesynapse.automation;
}
