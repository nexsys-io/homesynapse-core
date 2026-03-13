plugins {
    base
}

description = "Web dashboard: Preact SPA (static files packaged into JAR)"

// This module packages pre-built frontend static files.
// The actual Vite/Preact build is handled by package.json scripts.
// The Gradle task here copies the built output into the JAR's resources.
