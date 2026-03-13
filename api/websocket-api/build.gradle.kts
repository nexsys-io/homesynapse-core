plugins {
    id("homesynapse.java-conventions")
    `java-library`
}

description = "WebSocket API: event relay, subscription management, backpressure"

dependencies {
    implementation(project(":core:event-model"))
    implementation(project(":core:event-bus"))
}
