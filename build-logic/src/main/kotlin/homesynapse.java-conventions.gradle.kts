plugins {
    java
    id("com.diffplug.spotless")
}

// ---------------------------------------------------------------------------
// Version catalog access (required for precompiled script plugins in
// included builds — the type-safe accessors aren't generated here, so
// we use the VersionCatalog API instead)
// ---------------------------------------------------------------------------
val libs = versionCatalogs.named("libs")

// ---------------------------------------------------------------------------
// Java toolchain — pins the JDK version for all modules
// ---------------------------------------------------------------------------
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(
            libs.findVersion("java-language").get().toString().toInt()
        ))
    }
}

// ---------------------------------------------------------------------------
// Compiler flags — warnings are errors
// ---------------------------------------------------------------------------
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

// ---------------------------------------------------------------------------
// Repositories — all modules resolve from Maven Central only
// ---------------------------------------------------------------------------
repositories {
    mavenCentral()
}

// ---------------------------------------------------------------------------
// Testing — JUnit 5 with AssertJ
// ---------------------------------------------------------------------------
dependencies {
    "testImplementation"(libs.findLibrary("junit-jupiter").get())
    "testImplementation"(libs.findLibrary("assertj-core").get())
    "testRuntimeOnly"(libs.findLibrary("junit-platform-launcher").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    jvmArgs("-XX:+EnableDynamicAgentLoading")

    // FIX-1a (2026-09-05): every red run carries its own mechanism. One XML
    // block per test case — so a method's stdout (the structured log tokens) is
    // attributable to the method that produced it — and the FULL assertion
    // message + stack on the console, where today only "AssertionError at
    // X.java:NNN" reaches the CI log.
    reports.junitXml.isOutputPerTestCase = true
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = false
    }

    // Desk-only knob — CI never sets it. `-PvtParallelism=N` shapes the test
    // JVM's virtual-thread scheduler like a small runner (the GitHub runner has
    // 2 vCPUs = 2 carriers, and SQLite's native reads pin a carrier for their
    // duration), so a delivery stall that needs carrier starvation can be
    // reproduced on a 24-core desk. Absent property = JVM defaults, unchanged.
    project.findProperty("vtParallelism")?.toString()?.toIntOrNull()?.let { n ->
        jvmArgs(
            "-Djdk.virtualThreadScheduler.parallelism=$n",
            "-Djdk.virtualThreadScheduler.maxPoolSize=$n"
        )
    }
}

// ---------------------------------------------------------------------------
// Spotless — copyright header enforcement
// ---------------------------------------------------------------------------
spotless {
    java {
        licenseHeader("""
            /*
             * HomeSynapse Core
             * Copyright (c) ${'$'}YEAR NexSys. All rights reserved.
             */
        """.trimIndent())
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}

// ---------------------------------------------------------------------------
// Consistent JAR metadata
// ---------------------------------------------------------------------------
tasks.withType<Jar>().configureEach {
    manifest {
        attributes(
            "Implementation-Title"   to project.name,
            "Implementation-Version" to project.version,
            "Implementation-Vendor"  to "NexSys"
        )
    }
}
