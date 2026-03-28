// HomeSynapse Core / Copyright (c) 2026 NexSys. All rights reserved.
package com.homesynapse.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * Tests for {@link CustomCapability} — runtime-registered capability (final class, NOT record).
 */
@DisplayName("CustomCapability")
class CustomCapabilityTest {

    private static final ConfirmationPolicy POLICY = new ConfirmationPolicy(
            ConfirmationMode.DISABLED, List.of(), null, 5000L);

    private static CustomCapability sample() {
        return new CustomCapability(
                "zigbee.ikea_scene", 1, "zigbee",
                Map.of(), Map.of(), POLICY);
    }

    // -- Type structure -------------------------------------------------------

    @Nested
    @DisplayName("Type structure")
    class TypeStructureTests {

        @Test
        @DisplayName("is NOT a record (final class)")
        void isNotRecord() {
            assertThat(CustomCapability.class.isRecord()).isFalse();
        }

        @Test
        @DisplayName("is a final class")
        void isFinal() {
            assertThat(java.lang.reflect.Modifier.isFinal(
                    CustomCapability.class.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("implements Capability")
        void implementsCapability() {
            assertThat(sample()).isInstanceOf(Capability.class);
        }
    }

    // -- Construction ---------------------------------------------------------

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {

        @Test
        @DisplayName("all 6 fields accessible after construction")
        void allFieldsAccessible() {
            CustomCapability cap = sample();
            assertThat(cap.capabilityId()).isEqualTo("zigbee.ikea_scene");
            assertThat(cap.version()).isEqualTo(1);
            assertThat(cap.namespace()).isEqualTo("zigbee");
            assertThat(cap.attributeSchemas()).isEmpty();
            assertThat(cap.commandDefinitions()).isEmpty();
            assertThat(cap.confirmationPolicy()).isEqualTo(POLICY);
        }

        @Test
        @DisplayName("attributeSchemas map is unmodifiable (defensive copy)")
        void attributeSchemasUnmodifiable() {
            var mutable = new java.util.HashMap<String, AttributeSchema>();
            var cap = new CustomCapability("test", 1, "ext", mutable, Map.of(), POLICY);

            // Mutating the original map does not affect the capability
            mutable.put("injected", null);
            assertThat(cap.attributeSchemas()).isEmpty();
        }

        @Test
        @DisplayName("commandDefinitions map is unmodifiable (defensive copy)")
        void commandDefinitionsUnmodifiable() {
            var mutable = new java.util.HashMap<String, CommandDefinition>();
            var cap = new CustomCapability("test", 1, "ext", Map.of(), mutable, POLICY);

            mutable.put("injected", null);
            assertThat(cap.commandDefinitions()).isEmpty();
        }
    }

    // -- Null validation ------------------------------------------------------

    @Nested
    @DisplayName("Null validation")
    class NullValidationTests {

        @Test
        @DisplayName("null capabilityId throws NullPointerException")
        void nullCapabilityId() {
            assertThatNullPointerException().isThrownBy(() ->
                    new CustomCapability(null, 1, "ext", Map.of(), Map.of(), POLICY))
                    .withMessageContaining("capabilityId");
        }

        @Test
        @DisplayName("null namespace throws NullPointerException")
        void nullNamespace() {
            assertThatNullPointerException().isThrownBy(() ->
                    new CustomCapability("test", 1, null, Map.of(), Map.of(), POLICY))
                    .withMessageContaining("namespace");
        }

        @Test
        @DisplayName("null attributeSchemas throws NullPointerException")
        void nullAttributeSchemas() {
            assertThatNullPointerException().isThrownBy(() ->
                    new CustomCapability("test", 1, "ext", null, Map.of(), POLICY))
                    .withMessageContaining("attributeSchemas");
        }

        @Test
        @DisplayName("null commandDefinitions throws NullPointerException")
        void nullCommandDefinitions() {
            assertThatNullPointerException().isThrownBy(() ->
                    new CustomCapability("test", 1, "ext", Map.of(), null, POLICY))
                    .withMessageContaining("commandDefinitions");
        }

        @Test
        @DisplayName("null confirmationPolicy throws NullPointerException")
        void nullConfirmationPolicy() {
            assertThatNullPointerException().isThrownBy(() ->
                    new CustomCapability("test", 1, "ext", Map.of(), Map.of(), null))
                    .withMessageContaining("confirmationPolicy");
        }
    }

    // -- Equals / hashCode (manual implementation) ----------------------------

    @Nested
    @DisplayName("Equals and hashCode (manual implementation)")
    class EqualsHashCodeTests {

        @Test
        @DisplayName("identical instances are equal")
        void identicalEqual() {
            CustomCapability a = sample();
            CustomCapability b = sample();
            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        @DisplayName("same reference is equal to itself")
        void reflexive() {
            CustomCapability a = sample();
            assertThat(a).isEqualTo(a);
        }

        @Test
        @DisplayName("different capabilityId not equal")
        void differentCapabilityId() {
            CustomCapability a = sample();
            CustomCapability b = new CustomCapability("other", 1, "zigbee",
                    Map.of(), Map.of(), POLICY);
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("different version not equal")
        void differentVersion() {
            CustomCapability a = sample();
            CustomCapability b = new CustomCapability("zigbee.ikea_scene", 2, "zigbee",
                    Map.of(), Map.of(), POLICY);
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("different namespace not equal")
        void differentNamespace() {
            CustomCapability a = sample();
            CustomCapability b = new CustomCapability("zigbee.ikea_scene", 1, "zwave",
                    Map.of(), Map.of(), POLICY);
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("not equal to null")
        void notEqualToNull() {
            assertThat(sample()).isNotEqualTo(null);
        }

        @Test
        @DisplayName("not equal to non-CustomCapability object")
        void notEqualToOtherType() {
            assertThat(sample()).isNotEqualTo("not a capability");
        }
    }

    // -- toString (manual implementation) -------------------------------------

    @Nested
    @DisplayName("toString")
    class ToStringTests {

        @Test
        @DisplayName("toString contains capabilityId")
        void containsCapabilityId() {
            assertThat(sample().toString()).contains("zigbee.ikea_scene");
        }

        @Test
        @DisplayName("toString contains namespace")
        void containsNamespace() {
            assertThat(sample().toString()).contains("zigbee");
        }

        @Test
        @DisplayName("toString contains class name")
        void containsClassName() {
            assertThat(sample().toString()).contains("CustomCapability");
        }
    }
}
