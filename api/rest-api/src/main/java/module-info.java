/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

/**
 * REST API module — public-facing HTTP interface types for HomeSynapse.
 *
 * <p>Defines request/response records, service interfaces, pagination contracts,
 * authentication types, RFC 9457 error model, and ETag/caching contracts that
 * endpoint handlers (Phase 3) and the WebSocket API module (Block N) compile
 * against.</p>
 *
 * <p>Phase 2 has no {@code requires} directives — all types use Java standard
 * library types exclusively. Phase 3 will add requires for event-model,
 * device-model, state-store, automation, integration-api, persistence,
 * configuration, Jackson, and the chosen HTTP server library.</p>
 */
module com.homesynapse.api.rest {
    exports com.homesynapse.api.rest;
}
