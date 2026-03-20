plugins {
    id("homesynapse.java-conventions")
    `java-library`
}

description = "Automation engine: trigger-condition-action rules, cascade governor"

dependencies {
    api(project(":platform:platform-api"))
    api(project(":core:event-model"))
    api(project(":core:device-model"))
    api(project(":core:state-store"))
    api(project(":config:configuration"))
}
