plugins {
    id("homesynapse.java-conventions")
    `java-library`
}

description = "Systemd-specific platform implementation (health reporter, system paths)"

dependencies {
    implementation(project(":platform:platform-api"))
}
