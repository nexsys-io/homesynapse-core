plugins {
    id("homesynapse.java-conventions")
    `java-library`
}

description = "Integration runtime: supervisor, health state machine, thread allocation"

dependencies {
    api(project(":integration:integration-api"))
}
