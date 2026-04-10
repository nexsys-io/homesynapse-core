plugins {
    id("homesynapse.test-fixtures-conventions")
}

description = "Persistence: SQLite event store, telemetry, checkpoints, migrations"

dependencies {
    api(project(":platform:platform-api"))
    implementation(project(":core:event-model"))
    implementation(project(":core:state-store"))

    implementation(libs.sqlite.jdbc)
    implementation(libs.slf4j.api)

    // testFixtures dependencies — JUnit + AssertJ for the WriteCoordinatorContractTest
    // abstract class. The java-conventions plugin only adds these to testImplementation,
    // not testFixturesImplementation, so they must be declared explicitly here.
    testFixturesImplementation(libs.junit.jupiter)
    testFixturesImplementation(libs.assertj.core)
}
