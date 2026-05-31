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

import java.util.List;
import java.util.Set;

/**
 * Tests for {@link ProposedDevice} — focused on the {@code Set<HardwareIdentifier>}
 * refactor and the defensive-copy compact constructor introduced in AMD-44 Stage 1.
 */
@DisplayName("ProposedDevice")
class ProposedDeviceTest {

    private static final HardwareIdentifier HW_A =
            new HardwareIdentifier("zigbee_ieee", "00:11:22:33:44:55:66:77");

    @Nested
    @DisplayName("Construction and accessors")
    class ConstructionTests {

        @Test
        @DisplayName("accessors return the constructed values")
        void accessorsReturnValues() {
            ProposedDevice pd = new ProposedDevice(
                    Set.of(HW_A), "Acme", "Widget-1", List.of());

            assertThat(pd.hardwareIdentifiers()).containsExactly(HW_A);
            assertThat(pd.proposedManufacturer()).isEqualTo("Acme");
            assertThat(pd.proposedModel()).isEqualTo("Widget-1");
            assertThat(pd.proposedEntities()).isEmpty();
        }
    }

    @Nested
    @DisplayName("HardwareIdentifier set semantics")
    class HardwareIdentifierSetTests {

        @Test
        @DisplayName("duplicate HardwareIdentifiers collapse to a set of distinct elements")
        void duplicatesCollapse() {
            HardwareIdentifier duplicate =
                    new HardwareIdentifier("zigbee_ieee", "00:11:22:33:44:55:66:77");
            // Built from a collection that contains a duplicate (namespace, value) tuple.
            Set<HardwareIdentifier> withDuplicate = Set.copyOf(List.of(HW_A, duplicate));

            ProposedDevice pd = new ProposedDevice(
                    withDuplicate, "Acme", "Widget-1", List.of());

            assertThat(pd.hardwareIdentifiers()).containsExactly(HW_A);
        }

        @Test
        @DisplayName("returned hardwareIdentifiers set is unmodifiable")
        void returnedSetUnmodifiable() {
            ProposedDevice pd = new ProposedDevice(
                    Set.of(HW_A), "Acme", "Widget-1", List.of());

            assertThatThrownBy(() ->
                    pd.hardwareIdentifiers().add(new HardwareIdentifier("zwave_node", "0x01")))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("null hardwareIdentifiers throws NullPointerException")
        void nullHardwareIdentifiersThrows() {
            assertThatThrownBy(() ->
                    new ProposedDevice(null, "Acme", "Widget-1", List.of()))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("proposedEntities defensive copy")
    class ProposedEntitiesTests {

        @Test
        @DisplayName("returned proposedEntities list is unmodifiable")
        void returnedListUnmodifiable() {
            ProposedDevice pd = new ProposedDevice(
                    Set.of(HW_A), "Acme", "Widget-1", List.of());

            assertThatThrownBy(() -> pd.proposedEntities().add(null))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("null proposedEntities throws NullPointerException")
        void nullProposedEntitiesThrows() {
            assertThatThrownBy(() ->
                    new ProposedDevice(Set.of(HW_A), "Acme", "Widget-1", null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
