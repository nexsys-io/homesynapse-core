/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

/**
 * Tests for {@link AttributeSchema} — attribute definition within a capability.
 */
@DisplayName("AttributeSchema")
class AttributeSchemaTest {

    private static AttributeSchema fullSchema() {
        return new AttributeSchema(
                "brightness", AttributeType.INT,
                0, 100, 1,
                null, "%", "%",
                Set.of(Permission.READ, Permission.WRITE, Permission.NOTIFY),
                false, true);
    }

    private static AttributeSchema enumSchema() {
        return new AttributeSchema(
                "direction", AttributeType.ENUM,
                null, null, null,
                Set.of("import", "export", "bidirectional"),
                null, null,
                Set.of(Permission.READ),
                false, true);
    }

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {

        @Test
        @DisplayName("all 11 fields accessible after construction")
        void allFieldsAccessible() {
            AttributeSchema s = fullSchema();
            assertThat(s.attributeKey()).isEqualTo("brightness");
            assertThat(s.type()).isEqualTo(AttributeType.INT);
            assertThat(s.minimum()).isEqualTo(0);
            assertThat(s.maximum()).isEqualTo(100);
            assertThat(s.step()).isEqualTo(1);
            assertThat(s.validValues()).isNull();
            assertThat(s.unitSymbol()).isEqualTo("%");
            assertThat(s.canonicalUnitSymbol()).isEqualTo("%");
            assertThat(s.permissions()).containsExactlyInAnyOrder(
                    Permission.READ, Permission.WRITE, Permission.NOTIFY);
            assertThat(s.nullable()).isFalse();
            assertThat(s.persistent()).isTrue();
        }

        @Test
        @DisplayName("record has exactly 11 components")
        void exactlyElevenFields() {
            assertThat(AttributeSchema.class.getRecordComponents()).hasSize(11);
        }

        @Test
        @DisplayName("enum schema with validValues")
        void enumSchemaWithValidValues() {
            AttributeSchema s = enumSchema();
            assertThat(s.type()).isEqualTo(AttributeType.ENUM);
            assertThat(s.validValues()).containsExactlyInAnyOrder(
                    "import", "export", "bidirectional");
            assertThat(s.minimum()).isNull();
            assertThat(s.maximum()).isNull();
        }
    }

    @Nested
    @DisplayName("Nullable fields")
    class NullableFieldTests {

        @Test
        @DisplayName("minimum accepts null for unconstrained attributes")
        void nullMinimum() {
            AttributeSchema s = enumSchema();
            assertThat(s.minimum()).isNull();
        }

        @Test
        @DisplayName("maximum accepts null for unconstrained attributes")
        void nullMaximum() {
            AttributeSchema s = enumSchema();
            assertThat(s.maximum()).isNull();
        }

        @Test
        @DisplayName("step accepts null when not applicable")
        void nullStep() {
            AttributeSchema s = enumSchema();
            assertThat(s.step()).isNull();
        }

        @Test
        @DisplayName("validValues accepts null for non-enum types")
        void nullValidValues() {
            AttributeSchema s = fullSchema();
            assertThat(s.validValues()).isNull();
        }

        @Test
        @DisplayName("unitSymbol accepts null for dimensionless attributes")
        void nullUnitSymbol() {
            var s = new AttributeSchema("active", AttributeType.BOOLEAN,
                    null, null, null, null, null, null,
                    Set.of(Permission.READ), false, true);
            assertThat(s.unitSymbol()).isNull();
        }

        @Test
        @DisplayName("canonicalUnitSymbol accepts null when not applicable")
        void nullCanonicalUnitSymbol() {
            var s = new AttributeSchema("active", AttributeType.BOOLEAN,
                    null, null, null, null, null, null,
                    Set.of(Permission.READ), false, true);
            assertThat(s.canonicalUnitSymbol()).isNull();
        }
    }

    @Nested
    @DisplayName("Equals and hashCode")
    class EqualsHashCodeTests {

        @Test
        @DisplayName("identical schemas are equal")
        void identicalEqual() {
            assertThat(fullSchema()).isEqualTo(fullSchema());
            assertThat(fullSchema().hashCode()).isEqualTo(fullSchema().hashCode());
        }

        @Test
        @DisplayName("different attributeKey not equal")
        void differentNotEqual() {
            AttributeSchema a = fullSchema();
            AttributeSchema b = new AttributeSchema(
                    "level", AttributeType.INT, 0, 100, 1,
                    null, "%", "%",
                    Set.of(Permission.READ, Permission.WRITE, Permission.NOTIFY),
                    false, true);
            assertThat(a).isNotEqualTo(b);
        }
    }
}
