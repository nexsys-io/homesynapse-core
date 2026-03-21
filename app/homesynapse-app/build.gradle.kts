plugins {
    id("homesynapse.java-conventions")
    application
}

description = "Application assembly: main class, dependency wiring, jlink packaging"

application {
    mainClass.set("com.homesynapse.app.Main")
}

dependencies {
    // Platform layer
    implementation(project(":platform:platform-api"))
    implementation(project(":platform:platform-systemd"))

    // Core subsystems
    implementation(project(":core:event-model"))
    implementation(project(":core:device-model"))
    implementation(project(":core:state-store"))
    implementation(project(":core:persistence"))
    implementation(project(":core:event-bus"))
    implementation(project(":core:automation"))

    // Integration layer
    implementation(project(":integration:integration-api"))
    implementation(project(":integration:integration-runtime"))
    implementation(project(":integration:integration-zigbee"))

    // Configuration
    implementation(project(":config:configuration"))

    // API layer
    implementation(project(":api:rest-api"))
    implementation(project(":api:websocket-api"))

    // Observability
    implementation(project(":observability:observability"))

    // Lifecycle
    implementation(project(":lifecycle:lifecycle"))

    // Web dashboard (static files on classpath — not a JPMS module)
    runtimeOnly(project(":web-ui:dashboard"))

    // Logging implementation (only at the app level)
    runtimeOnly(libs.logback.classic)
    runtimeOnly(libs.logback.core)
    runtimeOnly(libs.logstash.logback.encoder)
}
