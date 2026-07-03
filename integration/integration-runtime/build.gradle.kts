plugins {
    id("homesynapse.library-conventions")
}

description = "Integration runtime: supervisor, health state machine, thread allocation"

dependencies {
    api(project(":integration:integration-api"))
    // M9.1 lockstep with `requires transitive com.homesynapse.event.bus` — the
    // assembly's Components exposes the bus Subscriber type on the exported API.
    api(project(":core:event-bus"))
    // M9.1 lockstep with the plain `requires org.slf4j` (implementation-only).
    implementation(libs.slf4j.api)

    testImplementation(project(":testing:test-support"))
    testImplementation(testFixtures(project(":integration:integration-api")))
    testImplementation(testFixtures(project(":core:event-model")))
}
