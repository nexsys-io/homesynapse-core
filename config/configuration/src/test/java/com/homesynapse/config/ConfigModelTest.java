/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link ConfigModel} — the immutable, validated in-memory
 * configuration model carrying the AMD-67 {@code (configSchemaMajor,
 * configSchemaMinor)} schema-version pair.
 *
 * <p>Covers the AMD-67 §5 {@code ConfigModelTest.majorMinorGuards} table row
 * plus the standing record contract (null rejection, defensive copies).</p>
 */
@DisplayName("ConfigModel")
class ConfigModelTest {

    private static final Instant LOADED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant FILE_MODIFIED_AT = Instant.parse("2026-01-01T00:00:01Z");

    /** Creates a new test instance. */
    ConfigModelTest() {
        // Explicit constructor per -Xlint:all -Werror requirement.
    }

    private static ConfigModel model(int major, int minor) {
        return new ConfigModel(major, minor, LOADED_AT, FILE_MODIFIED_AT, Map.of(), Map.of());
    }

    // ──────────────────────────────────────────────────────────────────
    // Construction
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {

        /** Creates a new test instance. */
        ConstructionTests() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Test
        @DisplayName("all 6 components accessible after construction")
        void allComponentsAccessible() {
            ConfigSection section = new ConfigSection("event_bus", Map.of("k", "v"), Map.of());
            ConfigModel model = new ConfigModel(
                    2, 1, LOADED_AT, FILE_MODIFIED_AT,
                    Map.of("event_bus", section), Map.of("event_bus", Map.of("k", "v")));

            assertThat(model.configSchemaMajor()).isEqualTo(2);
            assertThat(model.configSchemaMinor()).isEqualTo(1);
            assertThat(model.loadedAt()).isEqualTo(LOADED_AT);
            assertThat(model.fileModifiedAt()).isEqualTo(FILE_MODIFIED_AT);
            assertThat(model.sections()).containsKey("event_bus");
            assertThat(model.rawMap()).containsKey("event_bus");
        }

        @Test
        @DisplayName("record has exactly 6 components (AMD-67: 5 -> 6)")
        void exactlySixComponents() {
            assertThat(ConfigModel.class.getRecordComponents()).hasSize(6);
        }

        @Test
        @DisplayName("major/minor pair components are first, in (major, minor) order")
        void majorMinorComponentsFirst() {
            var components = ConfigModel.class.getRecordComponents();

            assertThat(components[0].getName()).isEqualTo("configSchemaMajor");
            assertThat(components[1].getName()).isEqualTo("configSchemaMinor");
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // AMD-67 §5 — major/minor guards
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Major/minor guards (AMD-67)")
    class MajorMinorGuardTests {

        /** Creates a new test instance. */
        MajorMinorGuardTests() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Test
        @DisplayName("configSchemaMajor < 1 throws IllegalArgumentException")
        void majorBelowOneRejected() {
            assertThatThrownBy(() -> model(0, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("configSchemaMajor");
        }

        @Test
        @DisplayName("negative configSchemaMajor throws IllegalArgumentException")
        void negativeMajorRejected() {
            assertThatThrownBy(() -> model(-1, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("configSchemaMajor");
        }

        @Test
        @DisplayName("configSchemaMinor < 0 throws IllegalArgumentException")
        void negativeMinorRejected() {
            assertThatThrownBy(() -> model(1, -1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("configSchemaMinor");
        }

        @Test
        @DisplayName("major = 1, minor = 0 is the minimum valid pair")
        void minimumValidPairAccepted() {
            ConfigModel model = model(1, 0);

            assertThat(model.configSchemaMajor()).isEqualTo(1);
            assertThat(model.configSchemaMinor()).isEqualTo(0);
        }

        @Test
        @DisplayName("minor resets independently of major (major bump with minor 0)")
        void majorBumpWithMinorZeroAccepted() {
            ConfigModel model = model(3, 0);

            assertThat(model.configSchemaMajor()).isEqualTo(3);
            assertThat(model.configSchemaMinor()).isZero();
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Null validation
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Null validation")
    class NullValidationTests {

        /** Creates a new test instance. */
        NullValidationTests() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Test
        @DisplayName("null loadedAt throws NullPointerException")
        void nullLoadedAt() {
            assertThatNullPointerException().isThrownBy(() ->
                    new ConfigModel(1, 0, null, FILE_MODIFIED_AT, Map.of(), Map.of()))
                    .withMessageContaining("loadedAt");
        }

        @Test
        @DisplayName("null fileModifiedAt throws NullPointerException")
        void nullFileModifiedAt() {
            assertThatNullPointerException().isThrownBy(() ->
                    new ConfigModel(1, 0, LOADED_AT, null, Map.of(), Map.of()))
                    .withMessageContaining("fileModifiedAt");
        }

        @Test
        @DisplayName("null sections throws NullPointerException")
        void nullSections() {
            assertThatNullPointerException().isThrownBy(() ->
                    new ConfigModel(1, 0, LOADED_AT, FILE_MODIFIED_AT, null, Map.of()))
                    .withMessageContaining("sections");
        }

        @Test
        @DisplayName("null rawMap throws NullPointerException")
        void nullRawMap() {
            assertThatNullPointerException().isThrownBy(() ->
                    new ConfigModel(1, 0, LOADED_AT, FILE_MODIFIED_AT, Map.of(), null))
                    .withMessageContaining("rawMap");
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Defensive copies
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Defensive copies")
    class DefensiveCopyTests {

        /** Creates a new test instance. */
        DefensiveCopyTests() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Test
        @DisplayName("sections map is unmodifiable")
        void sectionsUnmodifiable() {
            ConfigModel model = model(1, 0);

            assertThatThrownBy(() -> model.sections().put("x",
                    new ConfigSection("x", Map.of(), Map.of())))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("rawMap is unmodifiable")
        void rawMapUnmodifiable() {
            ConfigModel model = model(1, 0);

            assertThatThrownBy(() -> model.rawMap().put("x", "y"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
