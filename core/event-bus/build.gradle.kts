plugins {
    id("homesynapse.java-conventions")
    `java-library`
}

description = "In-process event bus implementation with virtual thread dispatch"

dependencies {
    api(project(":core:event-model"))
}
