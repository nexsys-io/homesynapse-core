plugins {
    id("homesynapse.java-conventions")
    `java-library`
}

description = "Lifecycle management: startup sequencing, graceful shutdown, watchdog"

dependencies {
    implementation(project(":platform:platform-api"))
    implementation(project(":core:event-model"))
    implementation(project(":observability:observability"))
}
