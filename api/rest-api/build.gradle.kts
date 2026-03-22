plugins {
    id("homesynapse.library-conventions")
}

description = "REST API: Javalin HTTP endpoints, RFC 9457 errors, pagination"

dependencies {
    implementation(project(":core:event-model"))
    implementation(project(":core:device-model"))
    implementation(project(":core:state-store"))
    implementation(project(":core:automation"))
    implementation(project(":observability:observability"))

    implementation(libs.javalin)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.datatype.jsr310)
}
