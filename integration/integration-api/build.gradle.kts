plugins {
    id("homesynapse.test-fixtures-conventions")
}

description = "Integration API: adapter-facing contracts, health, command handling"

dependencies {
    api(project(":core:event-model"))
    api(project(":core:device-model"))
    api(project(":core:state-store"))
    api(project(":core:persistence"))
    api(project(":config:configuration"))
}
