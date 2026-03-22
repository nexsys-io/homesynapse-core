/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.app;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

/**
 * ArchUnit rules encoding HomeSynapse's constitutional constraints.
 *
 * <p>These rules live in {@code homesynapse-app}'s test source set because
 * that module depends on all other modules, giving ArchUnit visibility to
 * the full codebase. Each rule maps to a specific locked decision or
 * architecture invariant.
 *
 * <p>Originally placed in {@code test-support}, these rules were relocated
 * here because test-support is a JPMS named module and ArchUnit is an
 * automatic module. The combination of {@code module-info.java} +
 * automatic module dependencies + {@code -Xlint:all -Werror} creates an
 * unresolvable catch-22. Test source sets run on the classpath, bypassing
 * JPMS entirely.
 *
 * @see HomeSynapseArchRulesTest
 * @see <a href="https://www.archunit.org/">ArchUnit</a>
 */
final class HomeSynapseArchRules {

    private HomeSynapseArchRules() {
        // Static rule container — not instantiable
    }

    // ──────────────────────────────────────────────────────────────────
    // Rule 1: No synchronized methods (LTD-11)
    //
    // Virtual thread compatibility requires ReentrantLock exclusively.
    // The synchronized keyword pins carrier threads, degrading the
    // virtual thread scheduler under contention.
    //
    // NOTE: This catches synchronized METHODS only. Synchronized blocks
    // are bytecode-level and not detectable via reflection/ArchUnit.
    // Synchronized blocks are enforced by grep in CI.
    // ──────────────────────────────────────────────────────────────────

    private static final ArchCondition<JavaMethod> NOT_BE_SYNCHRONIZED =
            new ArchCondition<>("not be synchronized") {
                @Override
                public void check(JavaMethod method, ConditionEvents events) {
                    if (method.getModifiers().contains(JavaModifier.SYNCHRONIZED)) {
                        events.add(SimpleConditionEvent.violated(method,
                                "Method " + method.getFullName()
                                        + " is synchronized — use ReentrantLock (LTD-11)"));
                    }
                }
            };

    /**
     * LTD-11: No {@code synchronized} methods anywhere in production code.
     * All locking must use {@link java.util.concurrent.locks.ReentrantLock}.
     */
    static final ArchRule NO_SYNCHRONIZED_METHODS =
            noMethods()
                    .that().areDeclaredInClassesThat().resideInAPackage("com.homesynapse..")
                    .should(NOT_BE_SYNCHRONIZED)
                    .as("LTD-11: No synchronized methods — use ReentrantLock for virtual thread safety");

    // ──────────────────────────────────────────────────────────────────
    // Rule 2: No direct time access (Clock injection enforcement)
    //
    // All time-dependent code must accept java.time.Clock as a parameter.
    // Direct calls to Instant.now(), System.currentTimeMillis(), or
    // Clock.systemUTC() are forbidden outside homesynapse-app and test.
    // ──────────────────────────────────────────────────────────────────

    /**
     * No direct {@code Instant.now()}, {@code System.currentTimeMillis()},
     * or {@code Clock.systemUTC()} outside the assembly, platform, and test modules.
     *
     * <p>{@code com.homesynapse.platform..} is excluded because {@code UlidFactory}
     * provides a convenience {@code generate()} method that uses {@code Clock.systemUTC()}
     * as a zero-config production path. The {@code Clock}-accepting overload exists
     * for test determinism. Evaluate removing the convenience method in Phase 3
     * (always-injected Clock).
     */
    static final ArchRule NO_DIRECT_TIME_ACCESS =
            noClasses()
                    .that().resideInAPackage("com.homesynapse..")
                    .and().resideOutsideOfPackage("com.homesynapse.app..")
                    .and().resideOutsideOfPackage("com.homesynapse.platform..")
                    .and().resideOutsideOfPackage("com.homesynapse.test..")
                    .should().callMethod(java.time.Instant.class, "now")
                    .orShould().callMethod(java.time.Clock.class, "systemUTC")
                    .orShould().callMethod(java.time.Clock.class, "systemDefaultZone")
                    .orShould().callMethod(System.class, "currentTimeMillis")
                    .orShould().callMethod(System.class, "nanoTime")
                    .as("Clock injection: No direct Instant.now(), System.currentTimeMillis(),"
                            + " or Clock.systemUTC() outside homesynapse-app and platform");

    // ──────────────────────────────────────────────────────────────────
    // Rule 3: No ServiceLoader (DECIDE-04)
    //
    // Integration factories are instantiated directly.
    // ServiceLoader is a runtime reflection mechanism incompatible with
    // the build-time enforcement model.
    // ──────────────────────────────────────────────────────────────────

    /**
     * DECIDE-04: No {@link java.util.ServiceLoader} usage anywhere.
     * Catches bytecode-level access to ServiceLoader (e.g., method calls).
     * Does not flag Javadoc {@code @link} type references — those are
     * stripped at compilation and absent from bytecode.
     */
    static final ArchRule NO_SERVICE_LOADER =
            noClasses()
                    .that().resideInAPackage("com.homesynapse..")
                    .should().accessClassesThat().belongToAnyOf(java.util.ServiceLoader.class)
                    .as("DECIDE-04: No ServiceLoader — factories instantiated directly");

    // ──────────────────────────────────────────────────────────────────
    // Rule 4: Dependency direction — core never depends on integration,
    // API, or higher layers
    //
    // Dependency direction: platform -> event -> core -> integration -> API -> app.
    // Reverse dependencies are architectural violations.
    // ──────────────────────────────────────────────────────────────────

    /**
     * Core modules must not depend on integration, API, or higher layers.
     */
    static final ArchRule NO_REVERSE_DEPENDENCIES =
            noClasses()
                    .that().resideInAnyPackage(
                            "com.homesynapse.event..",
                            "com.homesynapse.device..",
                            "com.homesynapse.state..",
                            "com.homesynapse.persistence..",
                            "com.homesynapse.automation..",
                            "com.homesynapse.config.."
                    )
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.homesynapse.integration..",
                            "com.homesynapse.api..",
                            "com.homesynapse.lifecycle..",
                            "com.homesynapse.app.."
                    )
                    .as("Dependency direction: core modules must not depend on"
                            + " integration, API, lifecycle, or app layers");

    // ──────────────────────────────────────────────────────────────────
    // Rule 5: No direct filesystem access in core modules
    //
    // Production code outside platform modules should not use
    // java.io.File or java.nio.file.Files directly — use the
    // PlatformPaths abstraction instead.
    // ──────────────────────────────────────────────────────────────────

    /**
     * Production code outside platform modules should not use
     * {@code java.io.File} or {@code java.nio.file.Files} directly.
     * Use {@code PlatformPaths} abstraction instead.
     */
    static final ArchRule NO_DIRECT_FILESYSTEM_IN_CORE =
            noClasses()
                    .that().resideInAnyPackage(
                            "com.homesynapse.event..",
                            "com.homesynapse.device..",
                            "com.homesynapse.state..",
                            "com.homesynapse.automation..",
                            "com.homesynapse.config.."
                    )
                    .should().accessClassesThat().belongToAnyOf(
                            java.io.File.class,
                            java.nio.file.Files.class
                    )
                    .as("Core modules must use PlatformPaths abstraction,"
                            + " not direct java.io.File/java.nio.file.Files");

    // ──────────────────────────────────────────────────────────────────
    // Rule 6: Package isolation per module
    //
    // No module should import from another module's .internal package.
    // JPMS enforces this at runtime; this rule catches it at build time
    // for non-modular compilation paths.
    // ──────────────────────────────────────────────────────────────────

    /**
     * No cross-module access to {@code .internal} packages.
     * JPMS enforces this at runtime; ArchUnit catches it at build time.
     */
    static final ArchRule NO_INTERNAL_PACKAGE_ACCESS =
            noClasses()
                    .that().resideInAPackage("com.homesynapse..")
                    .and().resideOutsideOfPackages("..internal..")
                    .should().dependOnClassesThat().resideInAPackage("..internal..")
                    .as("Package isolation: no cross-module access to .internal packages");

    // ──────────────────────────────────────────────────────────────────
    // Rule 7: No @JsonTypeInfo in event package
    //
    // Event serialization must use logical type names, not Java FQCNs.
    // Using JAVA_CLASS ties the storage format to Java class names,
    // making any rename/move a breaking change to persisted events.
    //
    // Approach: Ban @JsonTypeInfo entirely from the event package.
    // EventSerializer uses logical names — Jackson polymorphism via
    // @JsonTypeInfo is not the intended mechanism for events.
    // ──────────────────────────────────────────────────────────────────

    /**
     * No {@code @JsonTypeInfo} annotation in the event model package.
     * Events must use logical type names via {@code EventSerializer},
     * not Jackson's polymorphic type handling.
     */
    static final ArchRule NO_JSON_TYPE_INFO_IN_EVENTS =
            noClasses()
                    .that().resideInAPackage("com.homesynapse.event..")
                    .should().beAnnotatedWith("com.fasterxml.jackson.annotation.JsonTypeInfo")
                    .as("Event logical names: no @JsonTypeInfo in event package"
                            + " — use EventSerializer with logical type names");

    /**
     * Validates all rules against the given classes.
     *
     * <p>Convenience method for programmatic execution outside of
     * ArchUnit's {@code @ArchTest} annotation framework.
     *
     * @param classes the imported Java classes to check
     */
    static void checkAll(JavaClasses classes) {
        NO_SYNCHRONIZED_METHODS.check(classes);
        NO_DIRECT_TIME_ACCESS.check(classes);
        NO_SERVICE_LOADER.check(classes);
        NO_REVERSE_DEPENDENCIES.check(classes);
        NO_DIRECT_FILESYSTEM_IN_CORE.check(classes);
        NO_INTERNAL_PACKAGE_ACCESS.check(classes);
        NO_JSON_TYPE_INFO_IN_EVENTS.check(classes);
    }
}
