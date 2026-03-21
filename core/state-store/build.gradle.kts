plugins {
    id("homesynapse.java-conventions")
    `java-library`
}

description = "State store: projections, snapshots, query service"

dependencies {
    implementation(project(":core:event-model"))
    api(project(":core:device-model"))
}
