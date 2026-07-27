// ZipFile must be imported (not FQN'd inline): inside the script body the
// identifier `java` resolves to the java {} extension accessor, not the package.
import java.util.zip.ZipFile

plugins {
    // Bare `java`, deliberately NOT `homesynapse.java-conventions`: this is a
    // resources-only module (no Java sources, no toolchain need, no
    // spotless-java surface). The plugin exists solely so the module publishes
    // a real jar artifact that `runtimeOnly(project(":web-ui:dashboard"))` in
    // the app can carry onto the runtime classpath (DASH-SERVE, B-2).
    java
}

description = "Web dashboard: Preact SPA (static resources jar served by Javalin at /dashboard/)"

// ---------------------------------------------------------------------------
// This module ships a pre-built Preact SPA as a resources-only jar served by
// Javalin at /dashboard/ (Doc 13 §3.2-§3.3). The actual build is the Vite/npm
// pipeline (package.json); Gradle's job is to run that pipeline, stage the
// output under build/, and package it into the jar under /dashboard.
//
// DECOUPLING NOTE (so the Core lane's `./gradlew check` is unaffected): the
// npm pipeline attaches to the JAR TASK ONLY (jar → stageDashboard → npmBuild
// → npmInstall) — `processResources` is NEVER wired to npm, and staging lives
// under `build/staged-dashboard` (NOT `src/main/resources`, which the `java`
// plugin would sweep into every jar nondeterministically and drag onto test
// classpaths). The second half of the mechanism is the app-side
// `testRuntimeClasspath` exclusion (DASH-SERVE DP-2 in
// app/homesynapse-app/build.gradle.kts) — without it the app's tests would
// resolve this jar and pull npm into `check`. Proof obligations: `./gradlew
// check` must execute ZERO `:web-ui:dashboard:npmInstall|npmBuild|
// stageDashboard|jar` tasks, and the jar self-asserts that
// dashboard/index.html is actually inside the artifact. The frontend's own
// gate is `npm run verify` (frontend.yml); building the distribution
// (`installDist` / the image) does require Node + npm on the build host.
// ---------------------------------------------------------------------------

val npmCommand = if (System.getProperty("os.name").lowercase().contains("win")) "npm.cmd" else "npm"
val staticOut = layout.buildDirectory.dir("staged-dashboard")

val npmInstall by tasks.registering(Exec::class) {
    description = "Install frontend dependencies (npm ci)."
    group = "frontend"
    workingDir = projectDir
    inputs.files("package.json", "package-lock.json")
    outputs.dir("node_modules")
    commandLine(npmCommand, "ci")
}

val npmBuild by tasks.registering(Exec::class) {
    description = "Build the Preact SPA via Vite (production)."
    group = "frontend"
    dependsOn(npmInstall)
    workingDir = projectDir
    inputs.dir("src")
    inputs.files("package.json", "vite.config.ts", "tsconfig.json", "index.html")
    outputs.dir("dist")
    commandLine(npmCommand, "run", "build")
}

val stageDashboard by tasks.registering(Sync::class) {
    description = "Stage the built SPA under build/ for jar packaging."
    group = "frontend"
    dependsOn(npmBuild)
    from(layout.projectDirectory.dir("dist"))
    into(staticOut)
}

// The jar task is the ONLY npm-consuming seam: assemble → jar → stageDashboard.
tasks.named<Jar>("jar") {
    from(stageDashboard) { into("dashboard") }
    doLast {
        // Packaging self-assert (L1: the hop proves itself) — fail the build
        // if the SPA shell is not actually inside the artifact.
        val jarFile = archiveFile.get().asFile
        ZipFile(jarFile).use { zip ->
            requireNotNull(zip.getEntry("dashboard/index.html")) {
                "dashboard jar is missing dashboard/index.html — the npm pipeline did not stage the SPA"
            }
        }
    }
}

tasks.named<Delete>("clean") {
    // Default clean already deletes build/ (the staging dir). Keep the Vite
    // output, and sweep the pre-DASH-SERVE legacy staging location so a stale
    // src/main/resources/dashboard from an older checkout can never ride into
    // the jar via processResources.
    delete(layout.projectDirectory.dir("dist"))
    delete(layout.projectDirectory.dir("src/main/resources/dashboard"))
}
