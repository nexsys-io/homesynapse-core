plugins {
    id("homesynapse.java-conventions")
    `java-library`
}

description = "Zigbee integration adapter (ZNP/EZSP transport, ZCL, device profiles)"

dependencies {
    // LTD-17: Zigbee adapter depends on integration-api ONLY
    implementation(project(":integration:integration-api"))
}
