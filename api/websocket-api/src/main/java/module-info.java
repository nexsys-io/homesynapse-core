/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

/**
 * WebSocket API module for real-time event streaming, subscription
 * management, and backpressure-aware client delivery.
 *
 * <p>Depends on the REST API module for shared authentication types
 * ({@code ApiKeyIdentity}, {@code ApiException}) and error model
 * ({@code ProblemType}). Phase 3 will add dependencies on event-model,
 * event-bus, state-store, device-model, and Jackson.</p>
 */
module com.homesynapse.api.ws {
    requires transitive com.homesynapse.api.rest;

    exports com.homesynapse.api.ws;
}
