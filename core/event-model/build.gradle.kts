plugins {
    id("homesynapse.java-conventions")
    `java-library`
}

description = "Event model: types, envelope, publisher, store, and bus interfaces"

dependencies {
    api(project(":platform:platform-api"))

    api(libs.slf4j.api)
}
