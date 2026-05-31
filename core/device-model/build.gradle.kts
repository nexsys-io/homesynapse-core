plugins {
    id("homesynapse.test-fixtures-conventions")
}

description = "Device model: Device, Entity, Capability, registries, discovery"

dependencies {
    // M4.0b-4a: the AttributeValue hierarchy + AttributeType relocated out of
    // device-model into the com.homesynapse.value leaf. device-model re-exports
    // them on its public API (AttributeSchema, capabilities) → `requires transitive
    // com.homesynapse.value` ↔ api scope.
    api(project(":core:value-model"))
    api(project(":core:event-model"))
}
