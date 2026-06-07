plugins {
    id("homesynapse.library-conventions")
}

description = "Systemd-specific platform implementation (health reporter, system paths)"

dependencies {
    // `api` (not `implementation`): platform-api types (PlatformPaths/HealthReporter) appear
    // as supertypes on this module's public impl classes → JPMS `requires transitive` in
    // lockstep (see module-info.java). House rule: api ↔ requires transitive.
    api(project(":platform:platform-api"))
    implementation(libs.slf4j.api)

    testImplementation(project(":testing:test-support"))
}
