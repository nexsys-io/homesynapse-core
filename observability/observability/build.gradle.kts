plugins {
    id("homesynapse.library-conventions")
}

description = "Observability: health aggregation, JFR events, trace queries"

dependencies {
    api(project(":core:event-model"))
    implementation(project(":core:state-store"))
}
