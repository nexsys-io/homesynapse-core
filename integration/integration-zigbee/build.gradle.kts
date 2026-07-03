plugins {
    id("homesynapse.library-conventions")
}

description = "Zigbee integration adapter (ZNP/EZSP transport, ZCL, device profiles)"

dependencies {
    // LTD-17: Zigbee adapter depends on integration-api ONLY
    // api scope: IntegrationFactory, IntegrationAdapter, and integration-api types
    // appear in this module's public API signatures (ZigbeeAdapterFactory extends
    // IntegrationFactory, ZigbeeAdapter extends IntegrationAdapter, etc.)
    api(project(":integration:integration-api"))
    // M9.2 lockstep with the plain `requires com.fazecast.jSerialComm`:
    // interior-only per D-M92-1 — no jSerialComm type on any exported signature.
    implementation(libs.jserialcomm)
    // M9.2 lockstep with the plain `requires org.slf4j` (implementation-only,
    // LTD-15 structured logging — the M9.1 integration-runtime precedent pair).
    implementation(libs.slf4j.api)

    testImplementation(project(":testing:test-support"))
}
