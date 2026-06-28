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
 * against. M3.6e.1 adds the first production HTTP plumbing — the
 * {@link com.homesynapse.api.rest.RestFilters#installReadinessGate
 * RestFilters.installReadinessGate} method that registers a readiness
 * gate on {@code /api/*} traffic until the State Projection reaches
 * {@code SubscriberMode.LIVE}.</p>
 */
module com.homesynapse.api.rest {
    // M3.6e.1: ReadinessFilter consumes ReadinessSource (state-store) and
    // observes SubscriberMode (event-bus) via that source. The Javalin
    // Handler interface lives in io.javalin. SLF4J is used for DEBUG-level
    // rejection logs.
    requires transitive com.homesynapse.state;
    requires com.homesynapse.event.bus;

    // M7.5a: the run-query endpoints consume ExplanationService + RunExplanation/
    // RunSummary INTERNALLY (package-private handlers + the Object-erased
    // installRunQueryEndpoints gateway param), so this edge stays PLAIN
    // (non-transitive) — automation is not on rest-api's exported API.
    // build.gradle.kts already has implementation(project(":core:automation")).
    requires com.homesynapse.automation;

    // M7.5a: the causal-chain handler parses the raw command-parameter JSON string
    // (command_issued.parameters) into the wire `params` object. rest-api is the JSON
    // boundary (LTD-08) and already has implementation(libs.jackson.databind); used
    // only inside a package-private handler, so PLAIN requires.
    requires com.fasterxml.jackson.databind;

    requires io.javalin;
    requires org.slf4j;

    exports com.homesynapse.api.rest;
}
