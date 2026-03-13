plugins {
    id("homesynapse.java-conventions")
    `java-library`
}

description = "Shared test infrastructure: in-memory stores, fixtures, test clock"

dependencies {
    // Exposed as API so test consumers get these transitively
    api(project(":core:event-model"))
    api(project(":core:device-model"))
    api(project(":integration:integration-api"))

    api(libs.junit.jupiter)
    api(libs.assertj.core)
}
