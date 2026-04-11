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

    // M2.4: Jackson serialization infrastructure.
    // jackson-databind transitively pulls in jackson-core and jackson-annotations.
    implementation(libs.jackson.databind)
    implementation(libs.jackson.datatype.jsr310)
    implementation(libs.jackson.module.blackbird)

    // M2.4: Tests construct EventTypeRegistry with the 5 integration lifecycle
    // event records (IntegrationStarted, etc.). The test source set compiles on
    // the classpath (not the module path), so this does not require a JPMS
    // `requires com.homesynapse.integration` in module-info.java.
    testImplementation(project(":integration:integration-api"))

    // testFixtures dependencies — JUnit + AssertJ for the WriteCoordinatorContractTest
    // abstract class. The java-conventions plugin only adds these to testImplementation,
    // not testFixturesImplementation, so they must be declared explicitly here.
    testFixturesImplementation(libs.junit.jupiter)
    testFixturesImplementation(libs.assertj.core)
}
