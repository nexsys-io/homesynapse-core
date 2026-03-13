plugins {
    id("homesynapse.java-conventions")
    `java-library`
}

description = "Persistence: SQLite event store, telemetry, checkpoints, migrations"

dependencies {
    api(project(":core:event-model"))
    api(project(":core:state-store"))

    implementation(libs.sqlite.jdbc)
}
