/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

/**
 * Application assembly module for HomeSynapse Core.
 *
 * <p>This module sits at the apex of the dependency graph — it requires every
 * subsystem module but is not required by any other module. All {@code requires}
 * directives are non-transitive because this module exports no packages and has
 * no downstream consumers.
 *
 * <p>No {@code exports} clause is declared — the {@code com.homesynapse.app}
 * package is internal to the application entry point. No {@code uses} or
 * {@code provides} declarations in Phase 2; ServiceLoader-based integration
 * discovery will be added in Phase 3.
 */
module com.homesynapse.app {
    requires com.homesynapse.lifecycle;
    requires com.homesynapse.observability;
    requires com.homesynapse.event;
    requires com.homesynapse.device;
    requires com.homesynapse.state;
    requires com.homesynapse.persistence;
    requires com.homesynapse.event.bus;
    requires com.homesynapse.automation;
    requires com.homesynapse.integration;
    requires com.homesynapse.integration.runtime;
    requires com.homesynapse.integration.zigbee;
    requires com.homesynapse.config;
    requires com.homesynapse.api.rest;
    requires com.homesynapse.api.ws;
    requires com.homesynapse.platform;
}
