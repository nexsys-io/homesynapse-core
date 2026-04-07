plugins {
    id("homesynapse.test-fixtures-conventions")
}

description = "In-process event bus implementation with virtual thread dispatch"

dependencies {
    api(project(":core:event-model"))

    // testFixtures dependencies — JUnit + AssertJ for the CheckpointStoreContractTest
    // abstract class. The java-conventions plugin only adds these to testImplementation,
    // not testFixturesImplementation, so they must be declared explicitly here.
    testFixturesImplementation(libs.junit.jupiter)
    testFixturesImplementation(libs.assertj.core)
}
