plugins {
    id("homesynapse.library-conventions")
}

description = "REST API: Javalin HTTP endpoints, RFC 9457 errors, pagination"

dependencies {
    implementation(project(":core:event-model"))
    implementation(project(":core:device-model"))
    api(project(":core:state-store"))
    implementation(project(":core:automation"))
    implementation(project(":observability:observability"))

    // M3.6e.1: ReadinessFilter accepts a state-store ReadinessSource, returns
    // an RFC 9457 ProblemDetail body keyed by ProblemType.STATE_STORE_REPLAYING,
    // and writes via io.javalin.http.Handler. event-bus surfaces SubscriberMode
    // through ReadinessSource transitively, but we declare it explicitly to
    // match the explicit `requires com.homesynapse.event.bus` directive.
    implementation(project(":core:event-bus"))

    implementation(libs.javalin)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.datatype.jsr310)

    // M3.6e.1: ReadinessFilter logs rejection events at DEBUG via SLF4J
    // (LTD-15). Non-transitive — no SLF4J types appear in the rest-api
    // module's exported API.
    implementation(libs.slf4j.api)

    // M3.7 closeout: DlqStatusEndpointTest uses MinimalEventBusStub from
    // event-bus testFixtures instead of an inner-class StubBus, so a single
    // canonical lightweight EventBus stub is shared across unit tests.
    testImplementation(testFixtures(project(":core:event-bus")))

    // M4.0b-4a: four endpoint tests name com.homesynapse.value.AttributeValue/
    // EnumValue/StringValue (the AttributeValue hierarchy relocated out of
    // device-model). rest-api main does not reference value types, so no JPMS
    // `requires com.homesynapse.value` is added — the test source set compiles on
    // the classpath, so a testImplementation dependency suffices (relocation, 2026-05-31).
    testImplementation(project(":core:value-model"))
}
