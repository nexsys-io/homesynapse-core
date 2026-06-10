plugins {
    id("homesynapse.test-fixtures-conventions")
}

description = "Configuration: YAML loading, schema validation, secrets, hot reload"

dependencies {
    api(project(":core:event-model"))

    implementation(libs.snakeyaml.engine)
    implementation(libs.json.schema.validator)
    // module-info lockstep (Nick ruling 2026-06-10): jackson-databind otherwise
    // arrives only transitively via networknt's POM; slf4j backs LTD-15 logging.
    implementation(libs.jackson.databind)
    implementation(libs.slf4j.api)

    // testFixtures dependencies — InMemoryConfigAccess and TestConfigFactory
    testFixturesImplementation(libs.junit.jupiter)
    testFixturesImplementation(libs.assertj.core)

    // test dependencies for the validation test that uses testFixtures classes
    testImplementation(testFixtures(project(":config:configuration")))
}
