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

    exports com.homesynapse.automation;
}
