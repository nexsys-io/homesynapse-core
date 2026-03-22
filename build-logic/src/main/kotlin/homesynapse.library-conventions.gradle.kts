/*
 * HomeSynapse Core — Library module convention plugin.
 * Extends java-conventions with java-library for API/implementation dependency separation.
 * Applied by: 11 library modules directly + 6 modules via test-fixtures-conventions.
 */
plugins {
    id("homesynapse.java-conventions")
    `java-library`
}
