plugins {
    id("homesynapse.java-conventions")
    `java-library`
}

description = "State store: projections, snapshots, query service"

dependencies {
    api(project(":core:event-model"))
    api(project(":core:device-model"))
}
