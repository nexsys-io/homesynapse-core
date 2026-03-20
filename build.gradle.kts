plugins {
    base
}

group = "com.homesynapse"
version = "0.1.0-SNAPSHOT"

// ---------------------------------------------------------------------------
// Shared configuration applied to every subproject
// ---------------------------------------------------------------------------
subprojects {
    group = rootProject.group
    version = rootProject.version
}

// ---------------------------------------------------------------------------
// cleanAll — wipes build artifacts across all modules AND build-logic
// ---------------------------------------------------------------------------
tasks.register<Delete>("cleanAll") {
    description = "Clean all modules including the build-logic included build"
    group = "build"
    dependsOn("clean")
    delete(file("build-logic/build"))
}
