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
