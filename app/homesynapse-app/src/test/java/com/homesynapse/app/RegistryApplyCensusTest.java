/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.homesynapse.device.RegistryProjection;
import com.homesynapse.event.DeviceRemovedEvent;
import com.homesynapse.platform.identity.DeviceId;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * The {@code RegistryProjection.apply*} caller census (M9.5-DURb §6, DP-B7 —
 * the other half of REG-INV-1): the ArchUnit mutation rule proves nothing
 * writes a registry OUTSIDE the projection; this census pins WHO drives the
 * projection. Every sanctioned caller publishes the fact FIRST (write-ahead,
 * durable at return) and applies second — an apply() from an unpinned caller
 * is registry state the log may not carry, exactly the identity-drift class
 * AMD-99 closed. A new caller must arrive WITH a deliberate edit to this
 * census, never by drift.
 *
 * <p>Teeth (the {@link RegistryMutationRuleTest} idiom): the named
 * {@link UnpinnedApplyCallerFixture} is a deliberate violator the census
 * excludes BY NAME, while a second leg imports it in isolation and asserts
 * the SAME collector detects it — the census demonstrably bites on every
 * build.</p>
 */
@DisplayName("RegistryProjection.apply* caller census (REG-INV-1, M9.5-DURb §6)")
class RegistryApplyCensusTest {

    /** The projection's apply methods — the census's method-name vocabulary. */
    private static final Set<String> PROJECTION_APPLY_METHODS = Set.of(
            "applyDeviceRegistered", "applyEntityRegistered", "applyDeviceRemoved");

    /** The sanctioned production apply-callers (DP-B7 — edit DELIBERATELY). */
    private static final Set<String> SANCTIONED_APPLY_CALLERS = Set.of(
            "com.homesynapse.integration.zigbee.ZigbeeAdoptionSlice",
            "com.homesynapse.integration.zigbee.ZigbeeIntegrationAdapter",
            "com.homesynapse.lifecycle.RegistryProjectionSubscriber");

    /** The deliberate violator — an apply() call outside the sanctioned census. */
    static final class UnpinnedApplyCallerFixture {
        private final RegistryProjection projection;

        UnpinnedApplyCallerFixture(RegistryProjection projection) {
            this.projection = projection;
        }

        void applyOutsideTheCensus(DeviceId deviceId, DeviceRemovedEvent event) {
            projection.applyDeviceRemoved(deviceId, event);
        }
    }

    @Test
    @DisplayName("production callers of RegistryProjection.apply* are EXACTLY the "
            + "sanctioned three — a new caller needs a deliberate census edit")
    void productionApplyCallers_pinnedToTheCensus() {
        JavaClasses classes =
                new ClassFileImporter().importPackages("com.homesynapse");

        Set<String> callers = applyCallers(classes);
        // This class's own teeth fixture is the ONE named exclusion (the
        // RegistryMutationRuleTest idiom): the bite leg below proves the
        // collector sees it, so dropping it here can never hide a real caller.
        callers.remove(UnpinnedApplyCallerFixture.class.getName());

        assertThat(callers)
                .as("REG-INV-1: an unpublished apply() would be registry state "
                        + "not derived from the log — the census pins the "
                        + "write-ahead callers")
                .containsExactlyInAnyOrderElementsOf(SANCTIONED_APPLY_CALLERS);
    }

    @Test
    @DisplayName("the census DETECTS an unpinned apply-caller — it demonstrably bites")
    void censusDetectsAnUnpinnedCaller() {
        JavaClasses violator = new ClassFileImporter().importClasses(
                UnpinnedApplyCallerFixture.class, RegistryProjection.class,
                DeviceRemovedEvent.class, DeviceId.class);

        assertThat(applyCallers(violator))
                .contains(UnpinnedApplyCallerFixture.class.getName());
    }

    /** Every class with a method call targeting a projection apply method. */
    private static Set<String> applyCallers(JavaClasses classes) {
        return StreamSupport.stream(classes.spliterator(), false)
                .filter(RegistryApplyCensusTest::callsProjectionApply)
                .map(JavaClass::getFullName)
                .collect(Collectors.toCollection(HashSet::new));
    }

    private static boolean callsProjectionApply(JavaClass clazz) {
        for (JavaMethodCall call : clazz.getMethodCallsFromSelf()) {
            if (PROJECTION_APPLY_METHODS.contains(call.getTarget().getName())
                    && call.getTargetOwner().isAssignableTo(
                            "com.homesynapse.device.RegistryProjection")) {
                return true;
            }
        }
        return false;
    }
}
