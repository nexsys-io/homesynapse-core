/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;

import com.homesynapse.device.EntityRole;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Permits + construction + defensive-copy checks for the AMD-89 selector reshape. */
@DisplayName("Selector permits (AMD-89)")
class SelectorPermitTest {

    @Test
    @DisplayName("Selector permits exactly 7 subtypes")
    void permitsSeven() {
        assertThat(Selector.class.getPermittedSubclasses()).hasSize(7);
    }

    @Test
    @DisplayName("SemanticTagSelector constructs and defensively copies includedRoles")
    void semanticTagDefensiveCopy() {
        Set<EntityRole> roles = new HashSet<>(Set.of(EntityRole.PRIMARY));
        SemanticTagSelector selector =
                new SemanticTagSelector("room", "kitchen", MatchMode.EXACT, roles);

        assertThat(selector.includedRoles()).containsExactly(EntityRole.PRIMARY);

        roles.add(EntityRole.DIAGNOSTIC); // mutate the source — selector is unaffected
        assertThat(selector.includedRoles()).containsExactly(EntityRole.PRIMARY);
        assertThatThrownBy(() -> selector.includedRoles().add(EntityRole.CONFIG))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("group selectors carry includedRoles; Direct/Slug do not")
    void groupSelectorsCarryRoles() {
        assertThat(new AreaSelector("kitchen", Set.of(EntityRole.PRIMARY)).includedRoles())
                .containsExactly(EntityRole.PRIMARY);
        assertThat(new LabelSelector("downstairs", Set.of(EntityRole.DIAGNOSTIC)).includedRoles())
                .containsExactly(EntityRole.DIAGNOSTIC);
        assertThat(new TypeSelector("light", Set.of(EntityRole.PRIMARY, EntityRole.CONFIG))
                .includedRoles()).hasSize(2);
    }

    @Test
    @DisplayName("MatchMode has EXACT and NAMESPACE_PREFIX")
    void matchModeValues() {
        assertThat(MatchMode.values()).containsExactly(MatchMode.EXACT, MatchMode.NAMESPACE_PREFIX);
    }
}
