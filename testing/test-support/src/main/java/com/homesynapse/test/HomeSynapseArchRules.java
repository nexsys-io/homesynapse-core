/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.test;

/**
 * Tombstone — ArchUnit rules relocated to {@code com.homesynapse.app.HomeSynapseArchRules}
 * in {@code homesynapse-app/src/test/}.
 *
 * <p>ArchUnit is an automatic JPMS module. test-support is a named JPMS module
 * with {@code -Xlint:all -Werror}. The combination creates an unresolvable
 * catch-22: any form of {@code requires} for an automatic module triggers a
 * warning that {@code -Werror} promotes to a fatal error.
 *
 * <p>Test source sets run on the classpath, bypassing JPMS entirely. Moving
 * the rules to {@code homesynapse-app/src/test/} eliminates the conflict
 * without losing any architectural enforcement — homesynapse-app is the only
 * module with visibility to the full codebase anyway.
 *
 * <p>This file will be deleted after the build is verified green.
 *
 * @deprecated Use {@code com.homesynapse.app.HomeSynapseArchRules} instead.
 */
@Deprecated
final class HomeSynapseArchRules {

    private HomeSynapseArchRules() {
        // Tombstone — see class Javadoc
    }
}
