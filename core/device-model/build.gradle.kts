plugins {
    id("homesynapse.java-conventions")
    `java-library`
}

description = "Device model: Device, Entity, Capability, registries, discovery"

dependencies {
    api(project(":core:event-model"))
}
