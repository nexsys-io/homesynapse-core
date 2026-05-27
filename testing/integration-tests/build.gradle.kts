plugins {
    id("homesynapse.library-conventions")
}

description = "On-device integration tests (Pi 4 profile). Excluded from the default check task."

// ---------------------------------------------------------------------------
// Test task — Pi-profile-gated
//
// Integration tests exercise the real production stack (file-based SQLite +
// platform-thread executors + InProcessEventBus + StateProjection) under
// Pi-4-equivalent JVM constraints. They are deliberately excluded from the
// default `./gradlew check` flow because:
//   - they require real filesystem I/O (slow under WAL + fsync)
//   - they assert under tight memory budgets (-Xmx256m)
//   - the sustained variants run for tens of minutes (M3.4b)
//
// Enable via `-PpiProfile=throttled`. The optional `-PsustainedMinutes`
// overrides the sustained-load duration (default 60, CI: 10).
// ---------------------------------------------------------------------------
tasks.test {
    enabled = project.hasProperty("piProfile")

    if (project.hasProperty("piProfile")) {
        // Pi 4 equivalent JVM constraints
        jvmArgs(
            "-Xmx256m", "-Xms256m",
            "-XX:ActiveProcessorCount=4",
            "-XX:+UseG1GC", "-XX:MaxGCPauseMillis=100"
        )
        systemProperty("pi.profile", project.property("piProfile").toString())
    }

    if (project.hasProperty("sustainedMinutes")) {
        systemProperty(
            "sustained.minutes",
            project.property("sustainedMinutes").toString()
        )
    }
}

dependencies {
    // Production modules under test — exercised via real implementations
    testImplementation(project(":platform:platform-api"))
    testImplementation(project(":core:event-model"))
    testImplementation(project(":core:event-bus"))
    testImplementation(project(":core:state-store"))
    testImplementation(project(":core:persistence"))
    testImplementation(project(":integration:integration-api"))

    // Test fixtures from production modules — the only path to construct
    // package-private types (PersistenceTestHarness wraps SqlitePersistenceLifecycle;
    // InProcessEventBusFactory exposes the InProcessEventBus convenience
    // constructor; InMemoryProjectionAdvancer wraps any EventStore for the
    // StateProjection batch path).
    testImplementation(testFixtures(project(":core:event-model")))
    testImplementation(testFixtures(project(":core:event-bus")))
    testImplementation(testFixtures(project(":core:state-store")))
    testImplementation(testFixtures(project(":core:persistence")))

    // Cross-cutting test infrastructure
    testImplementation(project(":testing:test-support"))

    // M3.4b: Pi4SustainedLoadIT, Pi4D1SpikeIT, and CrashRecoveryIT emit
    // periodic progress logs (mandatory operator signal for runs that take
    // tens of minutes). The persistence module declares slf4j-api as
    // `implementation`-scoped, which the java-library plugin does not
    // propagate to consumers — declare it explicitly here.
    testImplementation(libs.slf4j.api)

    // M3.7 — capstone E2E harness depends on lifecycle's HomeSynapseCore,
    // the production composition root, plus the rest-api module for the
    // ProblemType enum surfaced in error responses.
    testImplementation(project(":lifecycle:lifecycle"))
    testImplementation(project(":api:rest-api"))

    // M3.7 — Awaitility for LIVE-mode polling (REC-14).
    // json-unit-assertj for shape-tolerant JSON assertions (REC-17).
    testImplementation(libs.awaitility)
    testImplementation(libs.json.unit.assertj)

    // SQLite JDBC driver at test runtime — the real WAL mode needs the
    // actual driver, not a mock.
    testRuntimeOnly(libs.sqlite.jdbc)
}
