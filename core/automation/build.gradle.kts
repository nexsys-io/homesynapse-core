plugins {
    id("homesynapse.library-conventions")
}

description = "Automation engine: trigger-condition-action rules, cascade governor"

dependencies {
    api(project(":platform:platform-api"))
    api(project(":core:event-model"))
    api(project(":core:device-model"))
    api(project(":core:state-store"))

    // M4.0b-4a: PendingCommand's javadoc references com.homesynapse.value
    // .AttributeValue; non-transitive ↔ implementation scope. Reachable transitively
    // via device too, declared explicitly at its use site (relocation, 2026-05-31).
    implementation(project(":core:value-model"))

    // M7.1: the automation_engine bus subscriber uses event-bus (a legal
    // core->core edge). The config dependency that FIX-07 proposed is NOT added —
    // core->config is banned by assertAllowedModuleDependencies at every scope; the
    // schema-registration + definition-document load ride the composition root.
    //
    // AB-3: bumped implementation -> api to lockstep with module-info's
    // `requires transitive com.homesynapse.event.bus`. The public
    // AutomationEngineAssembly seam returns the event-bus Subscriber interface on
    // the exported API, so event-bus must be a transitive/api dependency.
    api(project(":core:event-bus"))

    // M7.1: SLF4J for engine-internal logging (no SLF4J type on the public API).
    implementation(libs.slf4j.api)

    // M7.5a: StandardExplanationServiceTest seeds an InMemoryEventStore (the event-model
    // test fixture) to drive the log-derived ExplanationService projection. Test-scope only.
    testImplementation(testFixtures(project(":core:event-model")))
}
