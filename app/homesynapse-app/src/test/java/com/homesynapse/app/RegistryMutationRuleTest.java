/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.app;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.homesynapse.device.DeviceRegistry;
import com.homesynapse.device.Entity;
import com.homesynapse.device.EntityRegistry;
import com.homesynapse.device.RegistryProjection;
import com.homesynapse.event.DeviceRegisteredEvent;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The teeth proof for {@code REGISTRY_MUTATION_ONLY_VIA_PROJECTION}
 * (REG-INV-1, AMD-99 §4): a rule that never fails on anything is
 * indistinguishable from no rule. {@link DirectMutatorFixture} is a deliberate
 * violator — the codebase-wide {@code @ArchTest} sweep excludes it BY NAME (see
 * the rule definition), while this test imports it in isolation and asserts the
 * rule REJECTS it, so the rule demonstrably bites on every build.
 */
@DisplayName("REGISTRY_MUTATION_ONLY_VIA_PROJECTION teeth (REG-INV-1)")
class RegistryMutationRuleTest {

    /** The deliberate violator — a direct registry-mutator call outside the projection. */
    static final class DirectMutatorFixture {
        private final EntityRegistry registry;

        DirectMutatorFixture(EntityRegistry registry) {
            this.registry = registry;
        }

        void mutateDirectly(Entity entity) {
            registry.updateEntity(entity);
        }
    }

    @Test
    @DisplayName("the rule REJECTS a direct mutator call — it demonstrably bites")
    void ruleBitesOnADirectMutatorCall() {
        JavaClasses violator = new ClassFileImporter().importClasses(
                DirectMutatorFixture.class, EntityRegistry.class, Entity.class);

        assertThatThrownBy(() -> ruleWithoutTeethExclusion().check(violator))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("updateEntity")
                .hasMessageContaining("RegistryProjection");
    }

    @Test
    @DisplayName("the rule ACCEPTS RegistryProjection — the single sanctioned apply path")
    void ruleAcceptsTheProjection() {
        JavaClasses projection = new ClassFileImporter().importClasses(
                RegistryProjection.class, DeviceRegistry.class, EntityRegistry.class,
                DeviceRegisteredEvent.class);

        assertThatCode(() ->
                HomeSynapseArchRules.REGISTRY_MUTATION_ONLY_VIA_PROJECTION
                        .check(projection))
                .doesNotThrowAnyException();
    }

    /**
     * The production rule minus its named exclusion of {@link DirectMutatorFixture}
     * — rebuilt over the SAME shared condition
     * ({@code HomeSynapseArchRules.CALL_A_REGISTRY_MUTATOR}) so the bite test
     * exercises the real matching logic. The exclusion exists only so the
     * codebase-wide sweep stays green while this fixture exists; dropping it
     * here restores the violator to the rule's reach.
     */
    private static ArchRule ruleWithoutTeethExclusion() {
        return noClasses()
                .that().resideInAPackage("com.homesynapse..")
                .and().doNotHaveFullyQualifiedName(
                        "com.homesynapse.device.RegistryProjection")
                .should(HomeSynapseArchRules.CALL_A_REGISTRY_MUTATOR)
                .as("REG-INV-1 teeth check (the production condition, unexcluded)");
    }
}
