/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.config.test;

import com.homesynapse.config.ConfigModel;
import com.homesynapse.config.ConfigSection;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Static factory methods for creating valid {@link ConfigModel} and
 * {@link ConfigSection} instances with sensible defaults in tests.
 *
 * <p>Without this class, every test that needs a {@code ConfigModel} must
 * construct the full 5-field record manually, ensuring all non-null
 * constraints and {@link Map#copyOf(Map)} defensive copying. This class
 * provides one-liner factories for common scenarios.</p>
 *
 * <p>This class is a test fixture — it lives in the {@code testFixtures}
 * source set and is consumed by downstream modules via
 * {@code testFixtures(project(":config:configuration"))}.</p>
 *
 * @see ConfigModel
 * @see ConfigSection
 * @see InMemoryConfigAccess
 */
public final class TestConfigFactory {

    private TestConfigFactory() {
        // Utility class — no instantiation.
    }

    // ──────────────────────────────────────────────────────────────────
    // ConfigModel factories
    // ──────────────────────────────────────────────────────────────────

    /**
     * Creates a minimal valid {@link ConfigModel} with schema version 1,
     * current-time timestamps, empty sections, and an empty raw map.
     *
     * @return a valid ConfigModel with sensible defaults
     */
    public static ConfigModel minimalModel() {
        Instant now = Clock.systemUTC().instant();
        return new ConfigModel(1, now, now, Map.of(), Map.of());
    }

    /**
     * Creates a minimal valid {@link ConfigModel} using the provided clock
     * for both {@code loadedAt} and {@code fileModifiedAt} timestamps.
     *
     * <p>Enables deterministic testing with
     * {@link com.homesynapse.test.TestClock TestClock}.</p>
     *
     * @param clock the clock to use for timestamp generation; never {@code null}
     * @return a valid ConfigModel with clock-derived timestamps
     */
    public static ConfigModel minimalModel(Clock clock) {
        Instant now = clock.instant();
        return new ConfigModel(1, now, now, Map.of(), Map.of());
    }

    /**
     * Creates a {@link ConfigModel} containing a single section at the given
     * path with the given values.
     *
     * @param path   the dotted section path (e.g., {@code "persistence.retention"});
     *               never {@code null}
     * @param values the section values; never {@code null}
     * @return a valid ConfigModel with one section
     */
    public static ConfigModel modelWithSection(String path, Map<String, Object> values) {
        Instant now = Clock.systemUTC().instant();
        ConfigSection section = new ConfigSection(path, values, Map.of());
        return new ConfigModel(1, now, now, Map.of(path, section), Map.copyOf(values));
    }

    /**
     * Creates a {@link ConfigSection} with the given path and values, and
     * empty defaults.
     *
     * @param path   the dotted section path; never {@code null}
     * @param values the section values; never {@code null}
     * @return a valid ConfigSection
     */
    public static ConfigSection section(String path, Map<String, Object> values) {
        return new ConfigSection(path, values, Map.of());
    }

    /**
     * Creates a {@link ConfigSection} with the given path, values, and defaults.
     *
     * @param path     the dotted section path; never {@code null}
     * @param values   the effective runtime values; never {@code null}
     * @param defaults the JSON Schema default values; never {@code null}
     * @return a valid ConfigSection
     */
    public static ConfigSection section(String path, Map<String, Object> values,
                                        Map<String, Object> defaults) {
        return new ConfigSection(path, values, defaults);
    }

    /**
     * Creates a {@link ConfigModel} with an integration configuration section
     * at the path {@code "integrations.{integrationType}"}.
     *
     * <p>Matches the production path convention from Doc 06 where each
     * integration's configuration lives under {@code integrations.{type}:}.</p>
     *
     * @param integrationType the integration type (e.g., {@code "zigbee"},
     *                        {@code "mqtt"}); never {@code null}
     * @param values          the integration's configuration values;
     *                        never {@code null}
     * @return a valid ConfigModel with the integration section
     */
    public static ConfigModel integrationConfig(String integrationType,
                                                Map<String, Object> values) {
        String path = "integrations." + integrationType;
        return modelWithSection(path, values);
    }

    /**
     * Creates a {@link ConfigModel} with a realistic Zigbee integration
     * configuration: serial port, baud rate, and channel.
     *
     * <p>Useful for integration adapter tests that need plausible config
     * values that would pass schema validation.</p>
     *
     * @return a valid ConfigModel with Zigbee configuration
     */
    public static ConfigModel zigbeeConfig() {
        Map<String, Object> zigbeeValues = new HashMap<>();
        zigbeeValues.put("port", "/dev/ttyUSB0");
        zigbeeValues.put("baud_rate", 115200);
        zigbeeValues.put("channel", 15);
        return integrationConfig("zigbee", zigbeeValues);
    }

    /**
     * Creates a {@link ConfigModel} with {@code automationCount} automation stub
     * sections, each containing a minimal name and enabled flag.
     *
     * @param automationCount the number of automation stub sections to create;
     *                        must be {@code >= 0}
     * @return a valid ConfigModel with automation stubs
     */
    public static ConfigModel automationConfig(int automationCount) {
        Instant now = Clock.systemUTC().instant();
        Map<String, ConfigSection> sections = new HashMap<>();
        Map<String, Object> rawMap = new HashMap<>();

        for (int i = 1; i <= automationCount; i++) {
            String path = "automations.automation_" + i;
            Map<String, Object> values = Map.of(
                    "name", "Automation " + i,
                    "enabled", true
            );
            sections.put(path, new ConfigSection(path, values, Map.of()));
            rawMap.put("automation_" + i + ".name", "Automation " + i);
            rawMap.put("automation_" + i + ".enabled", true);
        }

        return new ConfigModel(1, now, now, sections, rawMap);
    }

    /**
     * Creates an empty {@link ConfigModel} with schema version 1 and all
     * timestamps set to {@link Instant#EPOCH}.
     *
     * <p>Useful for tests that need a valid but fully empty configuration.</p>
     *
     * @return a valid ConfigModel with epoch timestamps and no content
     */
    public static ConfigModel emptyConfig() {
        return new ConfigModel(1, Instant.EPOCH, Instant.EPOCH, Map.of(), Map.of());
    }
}
