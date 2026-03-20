/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

/**
 * Integration runtime module — supervisor, health state machine, and thread
 * allocation for integration adapters.
 *
 * <p>Depends on {@code com.homesynapse.integration} (integration-api) for
 * adapter-facing contracts. The {@code com.homesynapse.event} dependency
 * (event-model) is an implementation-only import for Phase 3 lifecycle
 * event production — no event-model types appear in the Phase 2 exported
 * API surface.</p>
 */
module com.homesynapse.integration.runtime {
    requires transitive com.homesynapse.integration;

    exports com.homesynapse.integration.runtime;
}
