/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.config;

import com.homesynapse.event.ConfigErrorEvent;
import com.homesynapse.event.ConfigValidationCompletedEvent;
import com.homesynapse.event.EventDraft;
import com.homesynapse.platform.identity.SystemId;
import com.homesynapse.platform.identity.Ulid;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end tests for the AMD-71 hybrid configuration directory layout —
 * the §5 test table: root+include merge, the one-level include restriction
 * (AMD-71-INV-02), the canonicalization-based path-traversal guard
 * (AMD-71-INV-01), compose-after-merge (§2.4), and the regenerable
 * {@code schemas/} cache.
 *
 * <p>These tests drive {@link StandardConfigurationService#load()} against
 * a real on-disk layout rooted at a temp directory standing in for
 * {@code PlatformPaths.configDir()} (DP-3 — the resolved {@code Path} is
 * constructor-injected; no platform edge).</p>
 */
@DisplayName("Configuration directory layout (AMD-71)")
class ConfigLayoutTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-06-10T00:00:00Z"), ZoneOffset.UTC);
    private static final SystemId SYSTEM_ID = SystemId.of(new Ulid(7L, 7L));

    private static final String EVENT_BUS_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "queue_capacity": { "type": "integer", "minimum": 1, "default": 1024 }
              },
              "additionalProperties": false
            }
            """;

    private static final String ZIGBEE_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "channel": { "type": "integer", "minimum": 11, "maximum": 26, "default": 15 }
              },
              "additionalProperties": false
            }
            """;

    @TempDir
    Path configDir;

    private final RecordingEventPublisher publisher = new RecordingEventPublisher();

    /** Creates a new test instance. */
    ConfigLayoutTest() {
        // Explicit constructor per -Xlint:all -Werror requirement.
    }

    // ──────────────────────────────────────────────────────────────────
    // Fixture helpers
    // ──────────────────────────────────────────────────────────────────

    private StandardSchemaRegistry registry() {
        StandardSchemaRegistry registry = new StandardSchemaRegistry(1, 0);
        registry.registerCoreSchema("event_bus", EVENT_BUS_SCHEMA);
        registry.registerIntegrationSchema("zigbee", ZIGBEE_SCHEMA);
        return registry;
    }

    private StandardConfigurationService service(StandardSchemaRegistry registry) {
        return new StandardConfigurationService(
                configDir, 1, 0, FIXED_CLOCK, SYSTEM_ID, publisher, registry,
                new JsonSchemaCompositeValidator(), List.of(), List.of());
    }

    private StandardConfigurationService service() {
        return service(registry());
    }

    private void writeRoot(String yaml) throws IOException {
        Files.writeString(configDir.resolve("homesynapse.yaml"), yaml);
    }

    private void writeIntegration(String fileName, String yaml) throws IOException {
        Path dir = configDir.resolve("integrations");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(fileName), yaml);
    }

    // ──────────────────────────────────────────────────────────────────
    // AMD-71 §5 — rootLoadsIntegrationIncludes
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("homesynapse.yaml + integrations/*.yaml merge into one model")
    void rootLoadsIntegrationIncludes() throws Exception {
        writeIntegration("zigbee.yaml", "channel: 20\n");
        writeRoot("""
                event_bus:
                  queue_capacity: 32
                integrations:
                  zigbee: !include integrations/zigbee.yaml
                """);

        ConfigModel model = service().load();

        assertThat(model.sections()).containsKeys("event_bus", "integrations.zigbee");
        ConfigSection zigbee = model.sections().get("integrations.zigbee");
        assertThat(zigbee.values()).containsEntry("channel", 20);
        assertThat(model.sections().get("event_bus").values())
                .containsEntry("queue_capacity", 32);
    }

    // ──────────────────────────────────────────────────────────────────
    // AMD-71 §5 — nestedIncludeRejected (AMD-71-INV-02)
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an included file with its own !include is FATAL (one level deep)")
    void nestedIncludeRejected() throws Exception {
        writeIntegration("inner.yaml", "leaf: true\n");
        writeIntegration("zigbee.yaml", """
                channel: 20
                extra: !include integrations/inner.yaml
                """);
        writeRoot("""
                integrations:
                  zigbee: !include integrations/zigbee.yaml
                """);

        assertThatThrownBy(() -> service().load())
                .isInstanceOf(ConfigurationLoadException.class)
                .hasMessageContaining("include");
    }

    // ──────────────────────────────────────────────────────────────────
    // AMD-71 §5 — pathTraversalRejected (AMD-71-INV-01, fail-closed)
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Path-traversal guard")
    class PathTraversalTests {

        /** Creates a new test instance. */
        PathTraversalTests() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Test
        @DisplayName("!include ../ escaping the integrations directory is rejected, path named")
        void dotDotEscapeRejected() throws Exception {
            // The target EXISTS inside the config dir — rejection must come
            // from containment, not from a missing file.
            Files.createDirectories(configDir.resolve("integrations"));
            Files.writeString(configDir.resolve("escape.yaml"), "stolen: true\n");
            writeRoot("""
                    integrations:
                      bad: !include integrations/../escape.yaml
                    """);

            assertThatThrownBy(() -> service().load())
                    .isInstanceOf(ConfigurationLoadException.class)
                    .hasMessageContaining("escape.yaml");
        }

        @Test
        @DisplayName("absolute-path !include is rejected, path named")
        void absolutePathRejected(@TempDir Path outside) throws Exception {
            Path target = outside.resolve("outside.yaml");
            Files.writeString(target, "stolen: true\n");
            Files.createDirectories(configDir.resolve("integrations"));
            String absolute = target.toString().replace('\\', '/');
            writeRoot("""
                    integrations:
                      bad: !include %s
                    """.formatted(absolute));

            assertThatThrownBy(() -> service().load())
                    .isInstanceOf(ConfigurationLoadException.class)
                    .hasMessageContaining("outside.yaml");
        }

        @Test
        @DisplayName("a file inside config dir but outside integrations/ is rejected")
        void rootSiblingIncludeRejected() throws Exception {
            Files.createDirectories(configDir.resolve("integrations"));
            Files.writeString(configDir.resolve("sibling.yaml"), "nope: true\n");
            writeRoot("""
                    integrations:
                      bad: !include sibling.yaml
                    """);

            assertThatThrownBy(() -> service().load())
                    .isInstanceOf(ConfigurationLoadException.class)
                    .hasMessageContaining("sibling.yaml");
        }

        @Test
        @DisplayName("symlink escaping the integrations directory is rejected (canonicalization)")
        void symlinkEscapeRejected(@TempDir Path outside) throws Exception {
            Path target = outside.resolve("outside.yaml");
            Files.writeString(target, "stolen: true\n");
            Path integrations = configDir.resolve("integrations");
            Files.createDirectories(integrations);
            boolean linked;
            try {
                Files.createSymbolicLink(integrations.resolve("link.yaml"), target);
                linked = true;
            } catch (IOException | UnsupportedOperationException | SecurityException e) {
                linked = false;
            }
            Assumptions.assumeTrue(linked,
                    "symbolic links are not supported in this environment");
            writeRoot("""
                    integrations:
                      bad: !include integrations/link.yaml
                    """);

            assertThatThrownBy(() -> service().load())
                    .isInstanceOf(ConfigurationLoadException.class)
                    .hasMessageContaining("link.yaml");
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // AMD-71 §5 — composeAfterMerge (§2.4)
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the composed schema validates the merged whole, not per-file")
    void composeAfterMerge() throws Exception {
        // channel 5 violates the zigbee fragment's minimum of 11. The value
        // arrives via !include, so the violation is only detectable if
        // validation runs against the post-merge document.
        writeIntegration("zigbee.yaml", "channel: 5\n");
        writeRoot("""
                integrations:
                  zigbee: !include integrations/zigbee.yaml
                """);

        ConfigModel model = service().load();

        // ERROR at startup: the key reverts to its schema default (DP-2).
        assertThat(model.sections().get("integrations.zigbee").values())
                .containsEntry("channel", 15);
        // Pin went 1 -> 2 at M6.4 (2026-06-11, R1/DP-10 ruling): a completed
        // validation pass with an ERROR issue now publishes one config_error
        // per ERROR alongside the validation summary. No ordering contract
        // between the two — select by type.
        assertThat(publisher.rootDrafts).hasSize(2);
        List<ConfigErrorEvent> errorEvents = publisher.rootDrafts.stream()
                .map(EventDraft::payload)
                .filter(ConfigErrorEvent.class::isInstance)
                .map(ConfigErrorEvent.class::cast)
                .toList();
        assertThat(errorEvents).hasSize(1);
        assertThat(errorEvents.get(0).path()).endsWith("channel");
        assertThat(errorEvents.get(0).severity()).isEqualTo("ERROR");
        assertThat(errorEvents.get(0).appliedDefault()).isEqualTo("15");
        List<ConfigValidationCompletedEvent> summaryEvents = publisher.rootDrafts.stream()
                .map(EventDraft::payload)
                .filter(ConfigValidationCompletedEvent.class::isInstance)
                .map(ConfigValidationCompletedEvent.class::cast)
                .toList();
        assertThat(summaryEvents).hasSize(1);
        assertThat(summaryEvents.get(0).severityCounts()).containsEntry("ERROR", 1);
    }

    // ──────────────────────────────────────────────────────────────────
    // AMD-71 §5 — schemasCacheRegenerable
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("deleting schemas/ and reloading reproduces an identical composed schema")
    void schemasCacheRegenerable() throws Exception {
        writeRoot("event_bus:\n  queue_capacity: 32\n");
        Path cacheFile = configDir.resolve("schemas").resolve("config.schema.json");

        service().load();
        assertThat(cacheFile).exists();
        String firstComposition = Files.readString(cacheFile);

        Files.delete(cacheFile);
        Files.delete(cacheFile.getParent());

        service(registry()).load();
        assertThat(cacheFile).exists();
        assertThat(Files.readString(cacheFile)).isEqualTo(firstComposition);
    }
}
