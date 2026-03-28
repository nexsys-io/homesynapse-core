/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * Tests for {@link CapabilityInstance} — specific capability binding on an entity.
 */
@DisplayName("CapabilityInstance")
class CapabilityInstanceTest {

    private static final ConfirmationPolicy POLICY = new ConfirmationPolicy(
            ConfirmationMode.EXACT_MATCH, List.of("on"), null, 5000L);

    private static CapabilityInstance sample() {
        return new CapabilityInstance("on_off", 1, "core", 0, Map.of(), Map.of(), POLICY);
    }

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {

        @Test
        @DisplayName("all 7 fields accessible after construction")
        void allFieldsAccessible() {
            CapabilityInstance ci = sample();
            assertThat(ci.capabilityId()).isEqualTo("on_off");
            assertThat(ci.version()).isEqualTo(1);
            assertThat(ci.namespace()).isEqualTo("core");
            assertThat(ci.featureMap()).isEqualTo(0);
            assertThat(ci.attributes()).isEmpty();
            assertThat(ci.commands()).isEmpty();
            assertThat(ci.confirmation()).isEqualTo(POLICY);
        }

        @Test
        @DisplayName("record has exactly 7 components")
        void exactlySevenFields() {
            assertThat(CapabilityInstance.class.getRecordComponents()).hasSize(7);
        }

        @Test
        @DisplayName("non-zero featureMap preserved")
        void nonZeroFeatureMap() {
            var ci = new CapabilityInstance("brightness", 1, "core", 0x03, Map.of(), Map.of(), POLICY);
            assertThat(ci.featureMap()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("Equals and hashCode")
    class EqualsHashCodeTests {

        @Test
        @DisplayName("identical instances are equal")
        void identicalEqual() {
            assertThat(sample()).isEqualTo(sample());
            assertThat(sample().hashCode()).isEqualTo(sample().hashCode());
        }

        @Test
        @DisplayName("different capabilityId not equal")
        void differentNotEqual() {
            CapabilityInstance a = sample();
            CapabilityInstance b = new CapabilityInstance("brightness", 1, "core", 0,
                    Map.of(), Map.of(), POLICY);
            assertThat(a).isNotEqualTo(b);
        }
    }
}
