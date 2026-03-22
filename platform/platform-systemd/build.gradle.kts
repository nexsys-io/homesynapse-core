plugins {
    id("homesynapse.library-conventions")
}

description = "Systemd-specific platform implementation (health reporter, system paths)"

dependencies {
    implementation(project(":platform:platform-api"))
}
