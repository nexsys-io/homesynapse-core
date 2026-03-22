plugins {
    id("homesynapse.library-conventions")
}

description = "In-process event bus implementation with virtual thread dispatch"

dependencies {
    api(project(":core:event-model"))
}
