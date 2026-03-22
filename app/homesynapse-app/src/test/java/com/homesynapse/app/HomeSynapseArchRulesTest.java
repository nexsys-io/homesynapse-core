/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.app;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Architecture enforcement tests for the entire HomeSynapse codebase.
 *
 * <p>These tests run on every build ({@code ./gradlew check}) and verify
 * that constitutional constraints encoded in {@link HomeSynapseArchRules}
 * are not violated by any module.
 *
 * <p>This test class lives in {@code homesynapse-app} because that module
 * depends on all other modules, giving ArchUnit visibility to the full
 * codebase via a single {@code @AnalyzeClasses} annotation.
 *
 * @see HomeSynapseArchRules
 */
@AnalyzeClasses(packages = "com.homesynapse")
class HomeSynapseArchRulesTest {

    @ArchTest
    static final ArchRule noSynchronizedMethods =
            HomeSynapseArchRules.NO_SYNCHRONIZED_METHODS;

    @ArchTest
    static final ArchRule noDirectTimeAccess =
            HomeSynapseArchRules.NO_DIRECT_TIME_ACCESS;

    @ArchTest
    static final ArchRule noServiceLoader =
            HomeSynapseArchRules.NO_SERVICE_LOADER;

    @ArchTest
    static final ArchRule noReverseDependencies =
            HomeSynapseArchRules.NO_REVERSE_DEPENDENCIES;

    @ArchTest
    static final ArchRule noDirectFilesystemInCore =
            HomeSynapseArchRules.NO_DIRECT_FILESYSTEM_IN_CORE;

    @ArchTest
    static final ArchRule noInternalPackageAccess =
            HomeSynapseArchRules.NO_INTERNAL_PACKAGE_ACCESS;

    @ArchTest
    static final ArchRule noJsonTypeInfoInEvents =
            HomeSynapseArchRules.NO_JSON_TYPE_INFO_IN_EVENTS;
}
