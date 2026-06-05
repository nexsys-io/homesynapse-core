/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests for {@link ProposedEntity} — discovery-pipeline proposed entity mapping
 * (AMD-44 §2.5.3 entityRole field + legality guard).
 */
@DisplayName("ProposedEntity")
class ProposedEntityTest {

    // -- Shape ----------------------------------------------------------------

    @Test
    @DisplayName("record has exactly 4 components")
    void exactlyFourComponents() {
        assertThat(ProposedEntity.class.getRecordComponents()).hasSize(4);
    }

    // -- entityRole defaulting and coercion -----------------------------------

    @Nested
    @DisplayName("entityRole")
    class EntityRoleTests {

        @Test
        @DisplayName("3-arg convenience ctor defaults entityRole to PRIMARY")
        void threeArgCtorDefaultsPrimary() {
            ProposedEntity pe = new ProposedEntity(0, EntityType.PLUG, List.of("on_off"));

            assertThat(pe.entityRole()).isEqualTo(EntityRole.PRIMARY);
        }

        @Test
        @DisplayName("null entityRole coerces to PRIMARY")
        void nullRoleCoercesToPrimary() {
            ProposedEntity pe = new ProposedEntity(0, EntityType.PLUG, List.of("on_off"), null);

            assertThat(pe.entityRole()).isEqualTo(EntityRole.PRIMARY);
        }

        @Test
        @DisplayName("explicit legal role is accepted (SWITCH + CONFIG, AMD-44 Worked Example 2)")
        void explicitRoleAccepted() {
            ProposedEntity pe = new ProposedEntity(
                    0, EntityType.SWITCH, List.of("on_off"), EntityRole.CONFIG);

            assertThat(pe.entityRole()).isEqualTo(EntityRole.CONFIG);
        }
    }

    // -- Legality matrix guard ------------------------------------------------

    @Nested
    @DisplayName("legality matrix")
    class LegalityTests {

        @Test
        @DisplayName("illegal (type, role) pairs are rejected with IAE naming both")
        void illegalRolePairRejected() {
            assertThatThrownBy(() ->
                    new ProposedEntity(0, EntityType.PLUG, List.of(), EntityRole.CONFIG))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("CONFIG")
                    .hasMessageContaining("PLUG");

            assertThatThrownBy(() ->
                    new ProposedEntity(0, EntityType.SENSOR, List.of(), EntityRole.CONFIG))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("CONFIG")
                    .hasMessageContaining("SENSOR");
        }
    }

    // -- Defensive copies -----------------------------------------------------

    @Nested
    @DisplayName("proposedCapabilities collection")
    class CollectionTests {

        @Test
        @DisplayName("proposedCapabilities is defensively copied and unmodifiable")
        void proposedCapabilitiesDefensivelyCopied() {
            List<String> source = new ArrayList<>(List.of("on_off"));
            ProposedEntity pe = new ProposedEntity(
                    0, EntityType.SWITCH, source, EntityRole.PRIMARY);

            source.add("toggle");

            assertThat(pe.proposedCapabilities()).containsExactly("on_off");
            assertThatThrownBy(() -> pe.proposedCapabilities().add("x"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("null proposedCapabilities throws NPE")
        void nullProposedCapabilitiesThrows() {
            assertThatThrownBy(() ->
                    new ProposedEntity(0, EntityType.SWITCH, null, EntityRole.PRIMARY))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
