/*
 * HomeSynapse Core — Test fixtures convention plugin.
 * Extends library-conventions with java-test-fixtures for modules that publish test doubles.
 * Applied by: event-model, device-model, state-store, persistence, integration-api, configuration.
 * Consumers use: testImplementation(testFixtures(project(":core:event-model")))
 */
plugins {
    id("homesynapse.library-conventions")
    `java-test-fixtures`
}
