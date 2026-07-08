/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.app;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import java.util.Set;

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
    //
    // com.homesynapse.config.. is exempt (PM ruling 2026-06-10): ratified
    // AMD-71/[AMD-71-A] designates config the filesystem consumer for its
    // own document tree, rooted at the configDir Path injected by the
    // composition root (no config→platform edge). The compensating
    // control is AMD-71-INV-01 canonicalization-containment — every
    // !include target toRealPath()-resolved and contained under
    // integrations/ — test-pinned by ConfigLayoutTest.
    // ──────────────────────────────────────────────────────────────────

    /**
     * Production code outside platform modules should not use
     * {@code java.io.File} or {@code java.nio.file.Files} directly.
     * Use {@code PlatformPaths} abstraction instead.
     *
     * <p>{@code com.homesynapse.config..} is deliberately absent from the
     * package list (PM ruling 2026-06-10): ratified AMD-71/[AMD-71-A]
     * makes the Configuration System the designated filesystem consumer,
     * rooted at the composition-root-injected {@code configDir}
     * {@code Path}. The compensating control is the AMD-71-INV-01
     * canonicalization-containment guard, test-pinned by
     * {@code ConfigLayoutTest}.
     */
    static final ArchRule NO_DIRECT_FILESYSTEM_IN_CORE =
            noClasses()
                    .that().resideInAnyPackage(
                            "com.homesynapse.event..",
                            "com.homesynapse.device..",
                            "com.homesynapse.state..",
                            "com.homesynapse.automation.."
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

    // ──────────────────────────────────────────────────────────────────
    // Rule 8: REST API must not access persistence directly (M3.6e.2)
    //
    // Defense-in-depth complement to JPMS: the rest-api module-info
    // already lacks `requires com.homesynapse.persistence`, so a direct
    // import would fail at compile time on the module path. This rule
    // catches accidental classpath leaks (e.g., a future test source set
    // that pulls persistence transitively) and documents the
    // composition-root intent — REST queries MUST flow through
    // StateQueryService, never through a SQLite store.
    // ──────────────────────────────────────────────────────────────────

    /**
     * M3.6e.2: REST endpoint handlers must read state through
     * {@code StateQueryService}, never by accessing persistence types
     * directly. Defense in depth on top of JPMS module visibility.
     */
    static final ArchRule QUERY_SERVICE_READ_ONLY =
            noClasses()
                    .that().resideInAPackage("com.homesynapse.api.rest..")
                    .should().accessClassesThat().resideInAPackage(
                            "com.homesynapse.persistence..")
                    .as("M3.6e.2: REST endpoints must not access persistence"
                            + " directly — use StateQueryService");

    // ──────────────────────────────────────────────────────────────────
    // Rule 9: REST query endpoints must not publish events (M3.6e.2)
    //
    // The REST surface introduced in M3.6e.2 is read-only (entity
    // queries + operational/admin status). Write operations have their
    // own surface (command issuance, M5+) and route through the proper
    // command-validator pipeline — they MUST NOT bypass it by calling
    // EventPublisher directly from a query handler.
    //
    // The brief sketched a `callMethodWhere(target(name("publish"))...)`
    // form. The simpler `accessClassesThat().belongToAnyOf(...)` form
    // catches the same violation (EventPublisher has no read-only
    // methods — any access to the type implies an intent to publish)
    // and follows the established style of NO_SERVICE_LOADER above.
    // ──────────────────────────────────────────────────────────────────

    /**
     * M3.6e.2: REST query/admin endpoints must not depend on
     * {@code EventPublisher}. Read-only surface.
     */
    static final ArchRule REST_ENDPOINTS_NO_EVENT_PUBLISHING =
            noClasses()
                    .that().resideInAPackage("com.homesynapse.api.rest..")
                    .should().accessClassesThat().belongToAnyOf(
                            com.homesynapse.event.EventPublisher.class)
                    .as("M3.6e.2: REST query endpoints must not publish events"
                            + " — read-only surface");

    // ──────────────────────────────────────────────────────────────────
    // Rule 10: Jackson isolation of the domain model (AMD-52-INV-02)
    //
    // The AttributeValue / event / device / state model must carry NO
    // Jackson dependency: no @JsonTypeInfo, no @Json* annotation, no
    // com.fasterxml.jackson.* import. All (de)serialization of these types
    // — including the AMD-52 AttributeValue tagged-union codec — lives
    // ONLY in com.homesynapse.persistence (the Jackson-isolation HARD
    // RULE). This is the bytecode-level complement to event-package-scoped
    // Rule 7: it covers the device-resident AttributeValue and the typed
    // StateChangedEvent payload, which Rule 7 alone does not reach.
    // ──────────────────────────────────────────────────────────────────

    /**
     * AMD-52-INV-02: the value/event/device/state domain model must not depend on
     * {@code com.fasterxml.jackson..}. The {@code AttributeValue} serde and all event-payload
     * (de)serialization is confined to {@code com.homesynapse.persistence}.
     */
    static final ArchRule NO_JACKSON_IN_DOMAIN_MODEL =
            noClasses()
                    .that().resideInAnyPackage(
                            "com.homesynapse.value..",
                            "com.homesynapse.event..",
                            "com.homesynapse.device..",
                            "com.homesynapse.state.."
                    )
                    .should().dependOnClassesThat().resideInAPackage("com.fasterxml.jackson..")
                    .as("AMD-52-INV-02: the AttributeValue/event/device/state model is"
                            + " Jackson-free — the AttributeValue codec lives only in"
                            + " com.homesynapse.persistence");

    // ──────────────────────────────────────────────────────────────────
    // Rule 11: Registry mutation only via RegistryProjection (REG-INV-1,
    // AMD-99 §4)
    //
    // The device and entity registries are projections of the event log:
    // every registry mutation flows through the single projection-apply
    // path in com.homesynapse.device.RegistryProjection, whose only
    // inputs are the registration/removal event types. Any other
    // production caller of a registry mutating method would create
    // registry state the log cannot reconstruct — exactly the identity
    // re-mint / orphan-row class AMD-99 closed. Enforcement ships WITH
    // the mechanism (R-B — no deferral).
    //
    // Reach: production classes of every module plus this app module's
    // own test tree (the only test code on this classpath). Non-app TEST
    // code is not scanned (the corrected-2026-06-13 reach note) and may
    // mutate registries freely for fixtures. The one named test-tree
    // exclusion below is the teeth fixture RegistryMutationRuleTest uses
    // to prove — on every build — that this rule actually bites.
    // ──────────────────────────────────────────────────────────────────

    /** The mutating method names of DeviceRegistry + EntityRegistry. */
    private static final Set<String> REGISTRY_MUTATORS = Set.of(
            "createDevice", "updateDevice", "removeDevice",
            "createEntity", "updateEntity", "removeEntity",
            "enableEntity", "disableEntity");

    /**
     * Package-private (not {@code private}) so {@code RegistryMutationRuleTest}
     * can rebuild the rule WITHOUT the teeth-fixture exclusion and prove the
     * exact matching logic rejects a direct mutator call.
     */
    static final ArchCondition<JavaClass> CALL_A_REGISTRY_MUTATOR =
            new ArchCondition<>("call a DeviceRegistry/EntityRegistry mutating method") {
                @Override
                public void check(JavaClass clazz, ConditionEvents events) {
                    for (JavaMethodCall call : clazz.getMethodCallsFromSelf()) {
                        if (!REGISTRY_MUTATORS.contains(call.getTarget().getName())) {
                            continue;
                        }
                        JavaClass owner = call.getTargetOwner();
                        if (owner.isAssignableTo("com.homesynapse.device.DeviceRegistry")
                                || owner.isAssignableTo(
                                        "com.homesynapse.device.EntityRegistry")) {
                            events.add(SimpleConditionEvent.satisfied(clazz,
                                    "Class " + clazz.getFullName() + " calls "
                                            + call.getTarget().getFullName()
                                            + " (" + call.getSourceCodeLocation() + ")"
                                            + " — registry mutation flows only through"
                                            + " RegistryProjection (REG-INV-1)"));
                        }
                    }
                }
            };

    /**
     * REG-INV-1 (AMD-99 §4): no production class other than
     * {@code com.homesynapse.device.RegistryProjection} may call the
     * registries' mutating methods.
     */
    static final ArchRule REGISTRY_MUTATION_ONLY_VIA_PROJECTION =
            noClasses()
                    .that().resideInAPackage("com.homesynapse..")
                    .and().doNotHaveFullyQualifiedName(
                            "com.homesynapse.device.RegistryProjection")
                    // The teeth fixture: RegistryMutationRuleTest imports it in
                    // ISOLATION and asserts the rule REJECTS it — the permanent
                    // proof the rule bites. Excluded here so the codebase-wide
                    // @ArchTest sweep stays green while the proof stays red.
                    .and().doNotHaveFullyQualifiedName(
                            "com.homesynapse.app.RegistryMutationRuleTest$DirectMutatorFixture")
                    .should(CALL_A_REGISTRY_MUTATOR)
                    .as("REG-INV-1 (AMD-99): registry mutation flows only through"
                            + " RegistryProjection — no other production class may call"
                            + " DeviceRegistry/EntityRegistry mutating methods");

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
        QUERY_SERVICE_READ_ONLY.check(classes);
        REST_ENDPOINTS_NO_EVENT_PUBLISHING.check(classes);
        NO_JACKSON_IN_DOMAIN_MODEL.check(classes);
        REGISTRY_MUTATION_ONLY_VIA_PROJECTION.check(classes);
    }
}
