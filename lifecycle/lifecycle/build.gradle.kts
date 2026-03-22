plugins {
    id("homesynapse.library-conventions")
}

description = "Lifecycle management: startup sequencing, graceful shutdown, watchdog"

dependencies {
    api(project(":observability:observability"))
    api(project(":core:event-model"))
    api(project(":platform:platform-api"))
    implementation(project(":config:configuration"))
}
