/**
 * Zigbee integration adapter module for HomeSynapse Core.
 *
 * <p>Provides the ZCL data model, coordinator abstraction, device profile system,
 * and adapter interfaces for the Zigbee protocol integration. Depends exclusively
 * on integration-api per LTD-17; all upstream core types (event-model, device-model,
 * state-store, persistence, configuration, platform-api) are available transitively
 * through integration-api's own transitive chain.
 */
module com.homesynapse.integration.zigbee {
    requires transitive com.homesynapse.integration;

    exports com.homesynapse.integration.zigbee;
}
