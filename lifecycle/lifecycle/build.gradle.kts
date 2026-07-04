plugins {
    id("homesynapse.library-conventions")
}

description = "Lifecycle management: startup sequencing, graceful shutdown, watchdog"

dependencies {
    api(project(":observability:observability"))
    api(project(":core:event-model"))
    api(project(":platform:platform-api"))
    implementation(project(":config:configuration"))

    // AB-3: the composition root assembles the device registries (InMemory*),
    // the automation_engine subscriber chain, and selects the platform
    // HealthReporter impl. `implementation` scope — none of these modules' types
    // appear on the lifecycle module's exported API (composition internals only),
    // matching the non-transitive `requires` directives in module-info.java.
    implementation(project(":core:device-model"))
    implementation(project(":core:automation"))
    implementation(project(":platform:platform-systemd"))

    // M3.6d-a: composition-root prerequisites. `api` scope matches the
    // `requires transitive` directives in module-info.java — these modules'
    // public types appear in the lifecycle module's public API surface
    // (HomeSynapseConfig fields, ThrowingStateQueryService interface
    // implementation) and in package-private types (SharedScheduler).
    api(project(":core:persistence"))
    api(project(":core:event-bus"))
    api(project(":core:state-store"))

    // M3.6d-b: HomeSynapseCore aggregates IntegrationEvents.LIFECYCLE_EVENT_CLASSES
    // into the event-type registry. M9.1 FLIPPED this to `api` scope in lockstep
    // with the `requires transitive com.homesynapse.integration` promotion: the
    // canonical 7-arg HomeSynapseCore constructor exposes List<IntegrationFactory>
    // (an integration-api type) on the exported public API.
    api(project(":integration:integration-api"))

    // M9.1: the composition root constructs IntegrationSupervisorAssembly and
    // holds the IntegrationSupervisor. `implementation` scope — runtime types are
    // referenced only inside HomeSynapseCore + a package-private accessor,
    // matching the plain `requires com.homesynapse.integration.runtime`.
    implementation(project(":integration:integration-runtime"))

    // M3.6e.1: HomeSynapseCore registers a ReadinessFilter from rest-api as
    // the Javalin before("/api/*") gate. `implementation` scope — only the
    // composition root references it. The matching JPMS `requires
    // com.homesynapse.api.rest;` is also non-transitive.
    implementation(project(":api:rest-api"))

    // M3.6e.1: HomeSynapseCore starts a Javalin server during step 12 of
    // the bootstrap and tunes its embedded Jetty thread pool via
    // QueuedThreadPool (from Jetty, transitively available through Javalin).
    // `implementation` scope — Javalin/Jetty types do not appear in the
    // lifecycle module's exported API.
    implementation(libs.javalin)

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

    // M3.7 fix round 1: HomeSynapseCoreTest's
    // mode_returnsLiveAfterProjectionCompletesReplay needs Awaitility polling
    // to await the bus's COLD → REPLAY → TRANSITION → LIVE FSM without using
    // System.nanoTime() (forbidden by D-04 / NO_DIRECT_TIME_ACCESS).
    testImplementation(libs.awaitility)

    // M3.7 closeout: NotifyingEventPublisherTest extends MinimalEventBusStub
    // (from event-bus testFixtures) instead of declaring its own inner-class
    // EventBus stub. The base provides no-op subscribe/unsubscribe/notify;
    // the test subclass adds notify recording.
    testImplementation(testFixtures(project(":core:event-bus")))

    // M9.4a (TEST-SCOPE): the hardware-free hero-loop + zigbee replay-purity
    // gates boot the real composition root with the REAL zigbee factory over
    // the scripted NCP (ZigbeeHardwareFreeRig, zigbee testFixtures). The ITs
    // live HERE (the RunPipelineReplaySafetyTest harness home) so they run at
    // the default check gate — testing/integration-tests is Pi-profile-gated.
    testImplementation(project(":integration:integration-zigbee"))
    testImplementation(testFixtures(project(":integration:integration-zigbee")))
}
