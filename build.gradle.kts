plugins {
    base
    alias(libs.plugins.modules.graph.assert)
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
// Module dependency direction enforcement (INV-RF-01, architectural layers)
// ---------------------------------------------------------------------------
moduleGraphAssert {
    maxHeight = 7  // deepest path: platform-api → ... → homesynapse-app
    allowed = arrayOf(
        // Platform layer
        ":platform:platform-api -> ",  // leaf — depends on nothing
        ":platform:platform-systemd -> :platform:platform-api",

        // Core layer — depends only on platform and other core
        ":core:.* -> :platform:platform-api",
        ":core:.* -> :core:.*",

        // Config layer — depends on core
        ":config:.* -> :core:.*",
        ":config:.* -> :platform:platform-api",

        // Integration layer — depends on core, config, platform
        ":integration:.* -> :core:.*",
        ":integration:.* -> :config:.*",
        ":integration:.* -> :platform:platform-api",
        ":integration:integration-api -> :integration:integration-api",  // self
        ":integration:integration-runtime -> :integration:integration-api",
        ":integration:integration-zigbee -> :integration:integration-api",

        // API layer — depends on core, config, integration-api, observability, and intra-layer
        ":api:.* -> :api:.*",
        ":api:.* -> :core:.*",
        ":api:.* -> :config:.*",
        ":api:.* -> :integration:integration-api",
        ":api:.* -> :observability:.*",

        // Observability — depends on core, platform
        ":observability:.* -> :core:.*",
        ":observability:.* -> :platform:platform-api",

        // Lifecycle — depends on everything except app
        ":lifecycle:.* -> :core:.*",
        ":lifecycle:.* -> :config:.*",
        ":lifecycle:.* -> :integration:.*",
        ":lifecycle:.* -> :api:.*",
        ":lifecycle:.* -> :observability:.*",
        ":lifecycle:.* -> :platform:.*",

        // App — depends on everything
        ":app:.* -> .*",

        // Test support — depends on core + integration-api
        ":testing:.* -> :core:.*",
        ":testing:.* -> :integration:integration-api",
        ":testing:.* -> :platform:platform-api",
    )
    // Explicitly forbidden: reverse dependencies
    restricted = arrayOf(
        ":core:.* -X> :integration:.*",     // Core cannot depend on integration
        ":core:.* -X> :api:.*",             // Core cannot depend on API
        ":core:.* -X> :app:.*",             // Core cannot depend on app
        ":core:.* -X> :lifecycle:.*",       // Core cannot depend on lifecycle
        ":platform:.* -X> :core:.*",        // Platform cannot depend on core
        ":integration:integration-api -X> :integration:integration-runtime",  // API cannot depend on runtime
    )
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
