plugins {
    id("homesynapse.library-conventions")
}

description = "Integration runtime: supervisor, health state machine, thread allocation"

dependencies {
    api(project(":integration:integration-api"))
}
