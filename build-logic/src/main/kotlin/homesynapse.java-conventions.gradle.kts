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
