plugins {
    id("homesynapse.test-fixtures-conventions")
}

description = "Integration API: adapter-facing contracts, health, command handling"

dependencies {
    api(project(":core:event-model"))
    api(project(":core:device-model"))
    api(project(":core:state-store"))
    api(project(":core:persistence"))
    api(project(":config:configuration"))

    // testFixtures dependencies — JUnit + AssertJ for StubIntegrationContextTest and
    // fixture classes. The java-conventions plugin only adds these to testImplementation,
    // not testFixturesImplementation, so they must be declared explicitly here.
    testFixturesImplementation(libs.junit.jupiter)
    testFixturesImplementation(libs.assertj.core)
    testFixturesImplementation(testFixtures(project(":core:event-model")))
    testFixturesImplementation(testFixtures(project(":core:device-model")))

    // test dependencies — the test source set needs access to event-model and
    // device-model test fixtures for InMemoryEventStore and TestEntityFactory
    // references in StubIntegrationContextTest.
    testImplementation(testFixtures(project(":core:event-model")))
    testImplementation(testFixtures(project(":core:device-model")))
}
