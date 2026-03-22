/*
 * HomeSynapse Core — Application convention plugin.
 * Extends java-conventions with the application plugin for homesynapse-app.
 * Applied by: homesynapse-app only.
 */
plugins {
    id("homesynapse.java-conventions")
    application
}

application {
    mainClass.set("com.homesynapse.app.Main")
}
