plugins {
    id("homesynapse.test-fixtures-conventions")
}

description = "Event model: types, envelope, publisher, store, and bus interfaces"

dependencies {
    api(project(":platform:platform-api"))

    // SLF4J is per-module (DECIDE-01, 2026-03-20). Each module that uses logging
    // declares its own implementation dependency. event-model's public API does not
    // expose SLF4J types, so api() scope was unjustified and created a Gradle/JPMS
    // mismatch (S4-01). Consumers must add their own SLF4J dependency.
    implementation(libs.slf4j.api)

    // testFixtures dependencies — JUnit + AssertJ for the EventStoreContractTest
    // abstract class. The java-conventions plugin only adds these to testImplementation,
    // not testFixturesImplementation, so they must be declared explicitly here.
    testFixturesImplementation(libs.junit.jupiter)
    testFixturesImplementation(libs.assertj.core)
}
