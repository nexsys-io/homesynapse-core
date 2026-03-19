/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

/**
 * Integration API — adapter-facing contracts, health reporting, command handling,
 * and lifecycle event types.
 *
 * <p>This module defines the contract boundary between protocol-specific
 * integration adapters (Zigbee, MQTT, cloud APIs) and the HomeSynapse
 * event-sourced core. Every integration module depends on these types;
 * the implementation (IntegrationSupervisor, thread management, health
 * state machine) lives in integration-runtime.</p>
 */
module com.homesynapse.integration {
    requires transitive com.homesynapse.platform;
    requires transitive com.homesynapse.event;
    requires transitive com.homesynapse.device;
    requires transitive com.homesynapse.state;
    requires transitive com.homesynapse.persistence;
    requires transitive java.net.http;

    exports com.homesynapse.integration;
}
