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

tasks.register<JavaExec>("runC2") {
    description = "Run C2: WAL Checkpoint Non-Blocking test"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.homesynapse.spike.wal.C2CheckpointTest")
    args = listOf("spike-wal-c2.db")
}

// C3 is orchestrated by kill-driver.sh, which manages writer/verify cycles and SIGKILL.
// This task invokes the shell script; it cannot be run via JavaExec directly.
tasks.register<Exec>("runC3") {
    description = "Run C3: Kill -9 Durability test (5 trials via kill-driver.sh)"
    val runtimeCp = sourceSets["main"].runtimeClasspath
    dependsOn("classes")
    workingDir = projectDir
    commandLine(
        "sh", "kill-driver.sh",
        runtimeCp.asPath,
        "spike-wal-c3.db",
        "5"
    )
}

tasks.register<JavaExec>("runC4") {
    description = "Run C4: Virtual Thread Compatibility test"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.homesynapse.spike.wal.C4VirtualThreadTest")
    args = listOf("spike-wal-c4.db")
}

tasks.register<JavaExec>("runC5") {
    description = "Run C5: Native Library Extraction test"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.homesynapse.spike.wal.C5JlinkNativeTest")
    args = listOf("spike-wal-c5.db")
}

// V3: Platform Thread Executor Pattern Validation (throughput + concurrency sub-tests).
// Run without JFR (functional validation):
tasks.register<JavaExec>("runV3") {
    description = "Run V3: Executor Pattern Validation (throughput + concurrency)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.homesynapse.spike.wal.V3ExecutorPatternTest")
    args = listOf("spike-wal-v3.db")
}

// Run with JFR pinning detection using custom settings file (threshold=0ns).
// Use this variant to confirm zero VT pinning from sqlite-jdbc.
tasks.register<JavaExec>("runV3Jfr") {
    description = "Run V3 with JFR pinning detection (vt-pinning.jfc, threshold=0ns)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.homesynapse.spike.wal.V3ExecutorPatternTest")
    args = listOf("spike-wal-v3.db")
    jvmArgs = listOf(
        "-XX:StartFlightRecording=filename=spike-v3-pinning.jfr,settings=${projectDir}/vt-pinning.jfc"
    )
}
