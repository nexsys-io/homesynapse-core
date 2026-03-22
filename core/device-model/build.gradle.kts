plugins {
    id("homesynapse.test-fixtures-conventions")
}

description = "Device model: Device, Entity, Capability, registries, discovery"

dependencies {
    api(project(":core:event-model"))
}
