plugins {
    id("homesynapse.java-conventions")
    `java-library`
}

description = "Persistence: SQLite event store, telemetry, checkpoints, migrations"

dependencies {
    api(project(":platform:platform-api"))
    implementation(project(":core:event-model"))
    implementation(project(":core:state-store"))

    implementation(libs.sqlite.jdbc)
}
