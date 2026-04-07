/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.config;

import com.homesynapse.config.test.InMemoryConfigAccess;
import com.homesynapse.config.test.TestConfigFactory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates that the configuration test fixtures produce well-formed,
 * contract-correct instances suitable for downstream test consumption.
 *
 * @see InMemoryConfigAccess
 * @see TestConfigFactory
 */
@DisplayName("Configuration Test Fixture Validation")
class InMemoryConfigAccessTest {

    /** Creates a new test instance. */
    InMemoryConfigAccessTest() {
        // Explicit constructor per -Xlint:all -Werror requirement.
    }

    // ──────────────────────────────────────────────────────────────────
    // InMemoryConfigAccess validation
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("InMemoryConfigAccess")
    class InMemoryConfigAccessTests {

        /** Creates a new test instance. */
        InMemoryConfigAccessTests() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Test
        @DisplayName("empty() getConfig returns empty map")
        void empty_getConfig_returnsEmptyMap() {
            InMemoryConfigAccess config = InMemoryConfigAccess.empty();

            assertThat(config.getConfig()).isEmpty();
            assertThat(config.size()).isZero();
        }

        @Test
        @DisplayName("of(key, value) returns correct value")
        void of_singleKeyValue_returnsCorrectValue() {
            InMemoryConfigAccess config = InMemoryConfigAccess.of("host", "localhost");

            assertThat(config.getString("host")).hasValue("localhost");
            assertThat(config.size()).isEqualTo(1);
        }

        @Test
        @DisplayName("getString returns empty when value is wrong type")
        void getString_returnsEmpty_whenWrongType() {
            InMemoryConfigAccess config = InMemoryConfigAccess.of("port", 8080);

            assertThat(config.getString("port")).isEmpty();
        }

        @Test
        @DisplayName("getInt handles Number coercion (Long → int)")
        void getInt_handlesNumberCoercion() {
            InMemoryConfigAccess config = InMemoryConfigAccess.of("count", 42L);

            assertThat(config.getInt("count")).hasValue(42);
        }

        @Test
        @DisplayName("getBoolean returns empty when key is missing")
        void getBoolean_returnsEmpty_whenMissing() {
            InMemoryConfigAccess config = InMemoryConfigAccess.empty();

            assertThat(config.getBoolean("enabled")).isEmpty();
        }

        @Test
        @DisplayName("builder creates config with multiple values")
        void builder_multipleValues_allAccessible() {
            InMemoryConfigAccess config = InMemoryConfigAccess.builder()
                    .putString("host", "192.168.1.1")
                    .putInt("port", 1883)
                    .putBoolean("ssl", true)
                    .build();

            assertThat(config.getString("host")).hasValue("192.168.1.1");
            assertThat(config.getInt("port")).hasValue(1883);
            assertThat(config.getBoolean("ssl")).hasValue(true);
            assertThat(config.size()).isEqualTo(3);
        }

        @Test
        @DisplayName("put after construction updates config")
        void put_afterConstruction_updatesConfig() {
            InMemoryConfigAccess config = InMemoryConfigAccess.empty();

            config.put("added", "value");

            assertThat(config.getString("added")).hasValue("value");
            assertThat(config.size()).isEqualTo(1);
        }

        @Test
        @DisplayName("getConfig returns defensive copy")
        void getConfig_returnsDefensiveCopy() {
            InMemoryConfigAccess config = InMemoryConfigAccess.of("key", "value");

            Map<String, Object> snapshot = config.getConfig();

            // Modifying the snapshot must not affect the internal state
            try {
                snapshot.put("injected", "bad");
            } catch (UnsupportedOperationException ignored) {
                // unmodifiable map — this is the expected behavior
            }

            assertThat(config.getConfig()).doesNotContainKey("injected");
            assertThat(config.size()).isEqualTo(1);
        }

        @Test
        @DisplayName("clear removes all values")
        void clear_removesAllValues() {
            InMemoryConfigAccess config = InMemoryConfigAccess.builder()
                    .putString("a", "1")
                    .putString("b", "2")
                    .build();

            config.clear();

            assertThat(config.size()).isZero();
            assertThat(config.getConfig()).isEmpty();
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // TestConfigFactory validation
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("TestConfigFactory")
    class TestConfigFactoryTests {

        /** Creates a new test instance. */
        TestConfigFactoryTests() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Test
        @DisplayName("minimalModel has valid fields (all non-null, schema v1)")
        void minimalModel_hasValidFields() {
            ConfigModel model = TestConfigFactory.minimalModel();

            assertThat(model.schemaVersion()).isEqualTo(1);
            assertThat(model.loadedAt()).isNotNull();
            assertThat(model.fileModifiedAt()).isNotNull();
            assertThat(model.sections()).isNotNull();
            assertThat(model.rawMap()).isNotNull();
        }

        @Test
        @DisplayName("minimalModel(Clock) uses provided clock for timestamps")
        void minimalModelWithClock_usesProvidedClock() {
            Instant fixedTime = Instant.parse("2026-03-15T10:30:00Z");
            Clock clock = Clock.fixed(fixedTime, ZoneOffset.UTC);

            ConfigModel model = TestConfigFactory.minimalModel(clock);

            assertThat(model.loadedAt()).isEqualTo(fixedTime);
            assertThat(model.fileModifiedAt()).isEqualTo(fixedTime);
        }

        @Test
        @DisplayName("section produces valid ConfigSection")
        void section_producesValidSection() {
            ConfigSection section = TestConfigFactory.section(
                    "persistence.retention",
                    Map.of("max_days", 30));

            assertThat(section.path()).isEqualTo("persistence.retention");
            assertThat(section.values()).containsEntry("max_days", 30);
            assertThat(section.defaults()).isEmpty();
        }

        @Test
        @DisplayName("zigbeeConfig has realistic values")
        void zigbeeConfig_hasRealisticValues() {
            ConfigModel model = TestConfigFactory.zigbeeConfig();

            assertThat(model.sections()).containsKey("integrations.zigbee");

            ConfigSection zigbee = model.sections().get("integrations.zigbee");
            assertThat(zigbee.values())
                    .containsEntry("port", "/dev/ttyUSB0")
                    .containsEntry("baud_rate", 115200)
                    .containsEntry("channel", 15);
        }
    }
}
