/*
 * HomeSynapse Core — WAL Validation Spike
 * Standalone subproject for empirical SQLite WAL mode validation on Pi 5 hardware.
 * See: homesynapse-core-docs/research/sqlite-wal-validation-spike-plan.md
 */

plugins {
    id("homesynapse.java-conventions")
}

dependencies {
    implementation(libs.sqlite.jdbc)
}

// ---------------------------------------------------------------------------
// Convenience tasks for running individual spike criteria.
// Usage: ./gradlew :spike:wal-validation:runC1 --args="path/to/test.db"
// ---------------------------------------------------------------------------
tasks.register<JavaExec>("runC1") {
    description = "Run C1: Append Throughput test"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.homesynapse.spike.wal.C1AppendThroughputTest")
    args = listOf("spike-wal-c1.db")
}
