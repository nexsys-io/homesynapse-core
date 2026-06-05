/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Tests for {@link EntityRole} — the UX-role classification axis (AMD-44 §2.5).
 */
@DisplayName("EntityRole")
class EntityRoleTest {

    @Test
    @DisplayName("exactly 3 values declared")
    void exactlyThreeValues() {
        assertThat(EntityRole.values()).hasSize(3);
    }

    @Test
    @DisplayName("declaration order is PRIMARY, DIAGNOSTIC, CONFIG")
    void declarationOrder() {
        assertThat(EntityRole.values()).containsExactly(
                EntityRole.PRIMARY,
                EntityRole.DIAGNOSTIC,
                EntityRole.CONFIG);
    }

    @ParameterizedTest
    @EnumSource(EntityRole.class)
    @DisplayName("valueOf round-trip for each value")
    void valueOfRoundTrip(EntityRole role) {
        assertThat(EntityRole.valueOf(role.name())).isEqualTo(role);
    }
}
