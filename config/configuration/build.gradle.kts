plugins {
    id("homesynapse.test-fixtures-conventions")
}

description = "Configuration: YAML loading, schema validation, secrets, hot reload"

dependencies {
    api(project(":core:event-model"))

    implementation(libs.snakeyaml.engine)
    implementation(libs.json.schema.validator)

    // testFixtures dependencies — InMemoryConfigAccess and TestConfigFactory
    testFixturesImplementation(libs.junit.jupiter)
    testFixturesImplementation(libs.assertj.core)

    // test dependencies for the validation test that uses testFixtures classes
    testImplementation(testFixtures(project(":config:configuration")))
}
