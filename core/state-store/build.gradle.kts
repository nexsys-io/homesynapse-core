plugins {
    id("homesynapse.test-fixtures-conventions")
}

description = "State store: projections, snapshots, query service"

dependencies {
    api(project(":core:event-model"))
    api(project(":core:device-model"))
    api(project(":core:event-bus"))

    implementation(libs.slf4j.api)

    // testFixtures dependencies — JUnit + AssertJ for the abstract contract test bases
    // (ViewCheckpointStoreContractTest, ProjectionAdvancerContractTest, SubscriberContractTest,
    // StateProjectionContractTest). The java-conventions plugin only adds these to
    // testImplementation, not testFixturesImplementation, so they must be declared
    // explicitly here.
    testFixturesImplementation(libs.junit.jupiter)
    testFixturesImplementation(libs.assertj.core)

    // testFixtures need event-model testFixtures for InMemoryEventStore + TestEventFactory:
    // InMemoryProjectionAdvancer (in our testFixtures) wraps an EventStore, typically an
    // InMemoryEventStore from event-model.
    testFixturesImplementation(testFixtures(project(":core:event-model")))
    testFixturesImplementation(testFixtures(project(":core:event-bus")))

    // test-support provides TestClock for deterministic time control. Needed
    // by both the testFixtures source set (the abstract contract tests use a
    // TestClock in their shared @BeforeEach) and the test source set (concrete
    // tests use it directly).
    testFixturesImplementation(project(":testing:test-support"))
    testImplementation(project(":testing:test-support"))

    // Test classes also need the event-model and event-bus testFixtures (any test file
    // that imports from another module's testFixtures needs BOTH declarations per the
    // M1.4 lesson).
    testImplementation(testFixtures(project(":core:event-model")))
    testImplementation(testFixtures(project(":core:event-bus")))
}
