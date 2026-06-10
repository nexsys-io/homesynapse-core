/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link ScopedConfigurationAccess} — the integration-scoped
 * {@link ConfigurationAccess} implementation over a validated
 * {@link ConfigModel}.
 *
 * <p>Covers the Doc 06 §8.4 / Doc 05 §3.8 scoping contract: an adapter sees
 * only its own {@code integrations.{type}} section, an unconfigured
 * integration receives an empty section (INV-CE-02 — zero-config is valid),
 * and the typed convenience accessors return empty on missing or
 * wrongly-typed keys.</p>
 */
@DisplayName("ScopedConfigurationAccess")
class ScopedConfigurationAccessTest {

    private static final Instant FIXED_TIME = Instant.parse("2026-01-01T00:00:00Z");

    /** Creates a new test instance. */
    ScopedConfigurationAccessTest() {
        // Explicit constructor per -Xlint:all -Werror requirement.
    }

    private static ConfigModel modelWithSections(Map<String, ConfigSection> sections) {
        Map<String, Object> rawMap = new HashMap<>();
        sections.forEach((path, section) -> rawMap.put(path, section.values()));
        return new ConfigModel(1, 0, FIXED_TIME, FIXED_TIME, sections, rawMap);
    }

    private static ConfigModel zigbeeModel() {
        Map<String, Object> zigbeeValues = Map.of(
                "port", "/dev/ttyUSB0",
                "baud_rate", 115200,
                "permit_join", false);
        Map<String, Object> mqttValues = Map.of(
                "broker_url", "tcp://localhost:1883");
        Map<String, Object> eventBusValues = Map.of(
                "dispatch_parallelism", 4);
        return modelWithSections(Map.of(
                "integrations.zigbee",
                new ConfigSection("integrations.zigbee", zigbeeValues, Map.of()),
                "integrations.mqtt",
                new ConfigSection("integrations.mqtt", mqttValues, Map.of()),
                "event_bus",
                new ConfigSection("event_bus", eventBusValues, Map.of())));
    }

    // ──────────────────────────────────────────────────────────────────
    // Scoping
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Scoping")
    class ScopingTests {

        /** Creates a new test instance. */
        ScopingTests() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Test
        @DisplayName("getConfig returns only the adapter's own section values")
        void getConfigReturnsOwnSection() {
            ScopedConfigurationAccess access =
                    new ScopedConfigurationAccess("zigbee", zigbeeModel());

            Map<String, Object> config = access.getConfig();

            assertThat(config).containsOnlyKeys("port", "baud_rate", "permit_join");
        }

        @Test
        @DisplayName("another integration's keys are not visible")
        void otherIntegrationInvisible() {
            ScopedConfigurationAccess access =
                    new ScopedConfigurationAccess("zigbee", zigbeeModel());

            assertThat(access.getConfig()).doesNotContainKey("broker_url");
            assertThat(access.getString("broker_url")).isEmpty();
        }

        @Test
        @DisplayName("global (non-integration) sections are not visible")
        void globalSectionInvisible() {
            ScopedConfigurationAccess access =
                    new ScopedConfigurationAccess("zigbee", zigbeeModel());

            assertThat(access.getConfig()).doesNotContainKey("dispatch_parallelism");
        }

        @Test
        @DisplayName("unconfigured integration gets an empty section (INV-CE-02)")
        void unconfiguredIntegrationGetsEmptySection() {
            ScopedConfigurationAccess access =
                    new ScopedConfigurationAccess("hue", zigbeeModel());

            assertThat(access.getConfig()).isEmpty();
            assertThat(access.getString("anything")).isEmpty();
            assertThat(access.getInt("anything")).isEmpty();
            assertThat(access.getBoolean("anything")).isEmpty();
        }

        @Test
        @DisplayName("empty model (zero-config first run) yields empty section")
        void emptyModelYieldsEmptySection() {
            ConfigModel empty = new ConfigModel(
                    1, 0, FIXED_TIME, FIXED_TIME, Map.of(), Map.of());
            ScopedConfigurationAccess access =
                    new ScopedConfigurationAccess("zigbee", empty);

            assertThat(access.getConfig()).isEmpty();
        }

        @Test
        @DisplayName("getConfig returns an unmodifiable map")
        void getConfigUnmodifiable() {
            ScopedConfigurationAccess access =
                    new ScopedConfigurationAccess("zigbee", zigbeeModel());

            assertThatThrownBy(() -> access.getConfig().put("x", "y"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Typed accessors
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Typed accessors")
    class TypedAccessorTests {

        /** Creates a new test instance. */
        TypedAccessorTests() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Test
        @DisplayName("getString returns present value for a string key")
        void getStringPresent() {
            ScopedConfigurationAccess access =
                    new ScopedConfigurationAccess("zigbee", zigbeeModel());

            assertThat(access.getString("port")).contains("/dev/ttyUSB0");
        }

        @Test
        @DisplayName("getInt returns present value for an integer key")
        void getIntPresent() {
            ScopedConfigurationAccess access =
                    new ScopedConfigurationAccess("zigbee", zigbeeModel());

            assertThat(access.getInt("baud_rate")).contains(115200);
        }

        @Test
        @DisplayName("getBoolean returns present value for a boolean key")
        void getBooleanPresent() {
            ScopedConfigurationAccess access =
                    new ScopedConfigurationAccess("zigbee", zigbeeModel());

            assertThat(access.getBoolean("permit_join")).contains(false);
        }

        @Test
        @DisplayName("missing key returns empty for every accessor")
        void missingKeyReturnsEmpty() {
            ScopedConfigurationAccess access =
                    new ScopedConfigurationAccess("zigbee", zigbeeModel());

            assertThat(access.getString("missing")).isEmpty();
            assertThat(access.getInt("missing")).isEmpty();
            assertThat(access.getBoolean("missing")).isEmpty();
        }

        @Test
        @DisplayName("wrongly-typed key returns empty, not a coerced value")
        void wrongTypeReturnsEmpty() {
            ScopedConfigurationAccess access =
                    new ScopedConfigurationAccess("zigbee", zigbeeModel());

            // port is a String; baud_rate is an Integer; permit_join is a Boolean.
            assertThat(access.getInt("port")).isEmpty();
            assertThat(access.getBoolean("baud_rate")).isEmpty();
            assertThat(access.getString("permit_join")).isEmpty();
        }

        @Test
        @DisplayName("null key throws NullPointerException on every accessor")
        void nullKeyRejected() {
            ScopedConfigurationAccess access =
                    new ScopedConfigurationAccess("zigbee", zigbeeModel());

            assertThatNullPointerException().isThrownBy(() -> access.getString(null));
            assertThatNullPointerException().isThrownBy(() -> access.getInt(null));
            assertThatNullPointerException().isThrownBy(() -> access.getBoolean(null));
        }
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
        @DisplayName("null integrationType throws NullPointerException")
        void nullIntegrationType() {
            assertThatNullPointerException().isThrownBy(() ->
                    new ScopedConfigurationAccess(null, zigbeeModel()))
                    .withMessageContaining("integrationType");
        }

        @Test
        @DisplayName("blank integrationType throws IllegalArgumentException")
        void blankIntegrationType() {
            assertThatThrownBy(() -> new ScopedConfigurationAccess("  ", zigbeeModel()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("integrationType");
        }

        @Test
        @DisplayName("null model throws NullPointerException")
        void nullModel() {
            assertThatNullPointerException().isThrownBy(() ->
                    new ScopedConfigurationAccess("zigbee", null))
                    .withMessageContaining("model");
        }

        @Test
        @DisplayName("implements ConfigurationAccess")
        void implementsConfigurationAccess() {
            ScopedConfigurationAccess access =
                    new ScopedConfigurationAccess("zigbee", zigbeeModel());

            assertThat(access).isInstanceOf(ConfigurationAccess.class);
        }
    }
}
