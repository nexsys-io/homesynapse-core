plugins {
    id("homesynapse.library-conventions")
    // M9.4a (TEST-SCOPE): FakeNcp + FakeSerialByteChannel + the hardware-free rig
    // promote to testFixtures so the cross-module composition-root gates consume
    // the scripted NCP (the established testFixtures(project(...)) pattern).
    `java-test-fixtures`
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
    // M9.3 lockstep with the plain `requires com.fasterxml.jackson.databind`:
    // interior-only JSON profile loading + device cache (tree model, no reflection).
    implementation(libs.jackson.databind)

    testImplementation(project(":testing:test-support"))
    // M9.4b §6.8 (F-12, TEST-SCOPE): the match-site catch is pinned by a
    // one-WARN assertion — logback backs slf4j in this module's test tree only
    // (ListAppender capture; no production dependency change).
    testImplementation(libs.logback.classic)

    // M9.4a (TEST-SCOPE): the rig's public surface exposes TestClock (the byte
    // channel is clock-stepped), so the fixture dependency is api-scoped.
    testFixturesApi(project(":testing:test-support"))
}
