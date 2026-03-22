plugins {
    id("homesynapse.library-conventions")
}

description = "WebSocket API: event relay, subscription management, backpressure"

dependencies {
    api(project(":api:rest-api"))
    implementation(project(":core:event-model"))
    implementation(project(":core:event-bus"))
}
