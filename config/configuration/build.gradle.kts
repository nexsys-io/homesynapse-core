plugins {
    id("homesynapse.test-fixtures-conventions")
}

description = "Configuration: YAML loading, schema validation, secrets, hot reload"

dependencies {
    api(project(":core:event-model"))

    implementation(libs.snakeyaml.engine)
    implementation(libs.json.schema.validator)
}
