plugins {
    base
}

description = "Web dashboard: Preact SPA (static files packaged for the jlink image)"

// ---------------------------------------------------------------------------
// This module ships a pre-built Preact SPA as static resources served by Javalin
// at /dashboard/ (Doc 13 §3.2-§3.3). The actual build is the Vite/npm pipeline
// (package.json); Gradle's job is to run that pipeline and stage the output into
// `src/main/resources/dashboard/` for packaging into the distribution.
//
// DECOUPLING NOTE (so the Core lane's `./gradlew check` is unaffected): these npm
// tasks are wired into `assemble`/`build` ONLY — never into `check`. The `base`
// plugin's `check` is empty, so the root aggregate `./gradlew check` does NOT
// require Node. The frontend's own gate is `npm run verify` (lint + typecheck +
// unit tests + production build + bundle-budget + contract-check), run in the
// frontend CI workflow (see ci/frontend.yml). Building the distribution image
// (`:web-ui:dashboard:build`) does require Node + npm on the build host.
// ---------------------------------------------------------------------------

val npmCommand = if (System.getProperty("os.name").lowercase().contains("win")) "npm.cmd" else "npm"
val staticOut = layout.projectDirectory.dir("src/main/resources/dashboard")

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
    description = "Stage the built SPA into module resources for packaging."
    group = "frontend"
    dependsOn(npmBuild)
    from(layout.projectDirectory.dir("dist"))
    into(staticOut)
}

// Build the static bundle as part of assemble; keep `check` Node-free.
tasks.named("assemble") { dependsOn(stageDashboard) }

tasks.named<Delete>("clean") {
    delete(staticOut, layout.projectDirectory.dir("dist"))
}
