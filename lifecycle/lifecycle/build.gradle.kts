plugins {
    id("homesynapse.library-conventions")
}

description = "Lifecycle management: startup sequencing, graceful shutdown, watchdog"

dependencies {
    api(project(":observability:observability"))
    api(project(":core:event-model"))
    api(project(":platform:platform-api"))
    implementation(project(":config:configuration"))

    // M3.6d-a: composition-root prerequisites. `api` scope matches the
    // `requires transitive` directives in module-info.java — these modules'
    // public types appear in the lifecycle module's public API surface
    // (HomeSynapseConfig fields, ThrowingStateQueryService interface
    // implementation) and in package-private types (SharedScheduler).
    api(project(":core:persistence"))
    api(project(":core:event-bus"))
    api(project(":core:state-store"))

    // M3.6d-b: HomeSynapseCore aggregates IntegrationEvents.LIFECYCLE_EVENT_CLASSES
    // into the event-type registry. `implementation` scope — IntegrationEvents is
    // referenced only inside the composition root, not exposed on the lifecycle
    // module's public API (matches the non-transitive `requires` directive).
    implementation(project(":integration:integration-api"))

    // M3.6d-a build-fix: SharedScheduler uses SLF4J internally for ERROR
    // logging from safelyInvoke(). Implementation scope per LTD-15 /
    // DECIDE-01 — SLF4J types do not appear in the lifecycle module's
    // exported API. The `implementation` scope is NOT propagated to
    // consumers' compile classpath; the matching `requires org.slf4j`
    // directive in module-info.java is what makes the import resolve at
    // compile time inside this module.
    implementation(libs.slf4j.api)

    // M3.6d-a: SharedSchedulerTest uses Awaitility-style polling to assert
    // periodic task invocations within deterministic bounds. test-support
    // provides Awaitility-compatible helpers and the NoRealIoExtension.
    testImplementation(project(":testing:test-support"))
}
