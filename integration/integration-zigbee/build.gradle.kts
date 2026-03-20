plugins {
    id("homesynapse.java-conventions")
    `java-library`
}

description = "Zigbee integration adapter (ZNP/EZSP transport, ZCL, device profiles)"

dependencies {
    // LTD-17: Zigbee adapter depends on integration-api ONLY
    // api scope: IntegrationFactory, IntegrationAdapter, and integration-api types
    // appear in this module's public API signatures (ZigbeeAdapterFactory extends
    // IntegrationFactory, ZigbeeAdapter extends IntegrationAdapter, etc.)
    api(project(":integration:integration-api"))
}
