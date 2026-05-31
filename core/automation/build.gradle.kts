plugins {
    id("homesynapse.library-conventions")
}

description = "Automation engine: trigger-condition-action rules, cascade governor"

dependencies {
    api(project(":platform:platform-api"))
    api(project(":core:event-model"))
    api(project(":core:device-model"))
    api(project(":core:state-store"))

    // M4.0b-4a: PendingCommand's javadoc references com.homesynapse.value
    // .AttributeValue; non-transitive ↔ implementation scope. Reachable transitively
    // via device too, declared explicitly at its use site (relocation, 2026-05-31).
    implementation(project(":core:value-model"))
}
