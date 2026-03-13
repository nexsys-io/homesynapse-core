plugins {
    id("homesynapse.java-conventions")
    `java-library`
}

description = "Integration API: adapter-facing contracts, health, command handling"

dependencies {
    api(project(":core:event-model"))
    api(project(":core:device-model"))
}
