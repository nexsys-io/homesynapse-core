/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Tests for {@link EntityType} — functional classification of device entities
 * and the AMD-44 §2.5.1 EntityRole legality matrix.
 */
@DisplayName("EntityType")
class EntityTypeTest {

    @Test
    @DisplayName("exactly 6 MVP values declared")
    void exactlySixValues() {
        assertThat(EntityType.values()).hasSize(6);
    }

    @Test
    @DisplayName("all expected MVP values present (source declaration order, DP-5)")
    void allExpectedValuesPresent() {
        assertThat(EntityType.values()).containsExactly(
                EntityType.LIGHT,
                EntityType.SWITCH,
                EntityType.PLUG,
                EntityType.SENSOR,
                EntityType.BINARY_SENSOR,
                EntityType.ENERGY_METER);
    }

    @ParameterizedTest
    @EnumSource(EntityType.class)
    @DisplayName("valueOf round-trip for each value")
    void valueOfRoundTrip(EntityType type) {
        assertThat(EntityType.valueOf(type.name())).isEqualTo(type);
    }

    // -- EntityRole legality matrix (AMD-44 §2.5.1) ---------------------------

    /**
     * The authoritative role matrix: each {@link EntityType} mapped to its exact
     * legal-role set per AMD-44 §2.5.1.
     */
    static Stream<Arguments> matrix() {
        return Stream.of(
                Arguments.of(EntityType.LIGHT,
                        EnumSet.of(EntityRole.PRIMARY, EntityRole.DIAGNOSTIC)),
                Arguments.of(EntityType.SWITCH,
                        EnumSet.of(EntityRole.PRIMARY, EntityRole.DIAGNOSTIC, EntityRole.CONFIG)),
                Arguments.of(EntityType.PLUG,
                        EnumSet.of(EntityRole.PRIMARY)),
                Arguments.of(EntityType.SENSOR,
                        EnumSet.of(EntityRole.PRIMARY, EntityRole.DIAGNOSTIC)),
                Arguments.of(EntityType.BINARY_SENSOR,
                        EnumSet.of(EntityRole.PRIMARY, EntityRole.DIAGNOSTIC)),
                Arguments.of(EntityType.ENERGY_METER,
                        EnumSet.of(EntityRole.PRIMARY, EntityRole.DIAGNOSTIC)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("matrix")
    @DisplayName("legalRoles() equals the exact AMD-44 matrix set per type")
    void matrixExactPerType(EntityType type, Set<EntityRole> expected) {
        assertThat(type.legalRoles()).isEqualTo(expected);
    }

    @ParameterizedTest
    @EnumSource(EntityType.class)
    @DisplayName("every type allows PRIMARY")
    void everyTypeAllowsPrimary(EntityType type) {
        assertThat(type.allows(EntityRole.PRIMARY)).isTrue();
    }

    @Test
    @DisplayName("allows() rejects roles outside the matrix")
    void allowsRejectsIllegal() {
        assertThat(EntityType.LIGHT.allows(EntityRole.CONFIG)).isFalse();
        assertThat(EntityType.PLUG.allows(EntityRole.DIAGNOSTIC)).isFalse();
        assertThat(EntityType.PLUG.allows(EntityRole.CONFIG)).isFalse();
    }

    @Test
    @DisplayName("allows(null) throws NullPointerException")
    void allowsNullThrows() {
        assertThatThrownBy(() -> EntityType.SWITCH.allows(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("role must not be null");
    }

    @Test
    @DisplayName("legalRoles() is immutable")
    void legalRolesImmutable() {
        assertThatThrownBy(() -> EntityType.LIGHT.legalRoles().add(EntityRole.CONFIG))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
