plugins {
    id("homesynapse.java-conventions")
    application
}

description = "Application assembly: main class, dependency wiring, jlink packaging"

application {
    mainClass.set("com.homesynapse.app.Main")
}

dependencies {
    // The app module wires everything together
    implementation(project(":platform:platform-api"))
    implementation(project(":platform:platform-systemd"))
    implementation(project(":core:event-model"))
    implementation(project(":core:device-model"))
    implementation(project(":core:state-store"))
    implementation(project(":core:persistence"))
    implementation(project(":core:event-bus"))
    implementation(project(":core:automation"))
    implementation(project(":integration:integration-api"))
    implementation(project(":integration:integration-runtime"))
    implementation(project(":integration:integration-zigbee"))
    implementation(project(":config:configuration"))
    implementation(project(":api:rest-api"))
    implementation(project(":api:websocket-api"))
    implementation(project(":observability:observability"))
    implementation(project(":lifecycle:lifecycle"))

    // Logging implementation (only at the app level)
    runtimeOnly(libs.logback.classic)
    runtimeOnly(libs.logback.core)
}
