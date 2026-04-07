plugins {
    id("homesynapse.test-fixtures-conventions")
}

description = "State store: projections, snapshots, query service"

dependencies {
    implementation(project(":core:event-model"))
    api(project(":core:device-model"))

    // testFixtures dependencies — JUnit + AssertJ for the ViewCheckpointStoreContractTest
    // abstract class. The java-conventions plugin only adds these to testImplementation,
    // not testFixturesImplementation, so they must be declared explicitly here.
    testFixturesImplementation(libs.junit.jupiter)
    testFixturesImplementation(libs.assertj.core)

    // test-support provides TestClock for the concrete InMemoryViewCheckpointStoreTest
    testImplementation(project(":testing:test-support"))
}
