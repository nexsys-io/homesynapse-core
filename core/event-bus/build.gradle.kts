plugins {
    id("homesynapse.test-fixtures-conventions")
}

description = "In-process event bus implementation with virtual thread dispatch"

dependencies {
    api(project(":core:event-model"))

    // testFixtures dependencies — JUnit + AssertJ for the CheckpointStoreContractTest
    // and EventBusContractTest abstract classes. The java-conventions plugin only adds
    // these to testImplementation, not testFixturesImplementation, so they must be
    // declared explicitly here.
    testFixturesImplementation(libs.junit.jupiter)
    testFixturesImplementation(libs.assertj.core)

    // testFixtures need event-model testFixtures for InMemoryEventStore, TestEventFactory
    // (M1.7: EventBusContractTest uses InMemoryEventStore to back InMemoryEventBus,
    // and TestEventFactory for creating test events with customizable fields).
    testFixturesImplementation(testFixtures(project(":core:event-model")))

    // Test classes also need event-model testFixtures (lesson from M1.4: any test file
    // that imports from another module's testFixtures needs BOTH
    // testFixturesImplementation(testFixtures(...)) AND testImplementation(testFixtures(...))
    // declarations).
    testImplementation(testFixtures(project(":core:event-model")))
}
