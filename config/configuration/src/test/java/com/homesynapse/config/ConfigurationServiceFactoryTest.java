/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.config;

import com.homesynapse.platform.identity.SystemId;
import com.homesynapse.platform.identity.Ulid;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ConfigurationServiceFactory} — the public assembly entry
 * point for {@link ConfigurationService} (AB-3).
 *
 * <p>Time is injected via {@code Clock.fixed} (§4c); no wall-clock access.</p>
 */
@DisplayName("ConfigurationServiceFactory")
class ConfigurationServiceFactoryTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-06-19T00:00:00Z"), ZoneOffset.UTC);
    private static final SystemId SYSTEM_ID = SystemId.of(new Ulid(9L, 9L));

    private static final String DEMO_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "enabled": { "type": "boolean" }
              },
              "additionalProperties": false
            }
            """;

    @TempDir
    Path configDir;

    private final RecordingEventPublisher publisher = new RecordingEventPublisher();

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    ConfigurationServiceFactoryTest() {
    }

    @Test
    @DisplayName("create returns a non-null service and the schema registry it uses")
    void buildsAssembly() {
        ConfigurationServiceFactory.Assembly assembly = ConfigurationServiceFactory.create(
                configDir, FIXED_CLOCK, SYSTEM_ID, publisher);

        assertThat(assembly.service()).isNotNull();
        assertThat(assembly.schemaRegistry()).isNotNull();
    }

    @Test
    @DisplayName("the assembled service loads a config dir; the (major,minor) pair is "
            + "consistent by construction")
    void buildsLoadableService() throws Exception {
        ConfigurationServiceFactory.Assembly assembly = ConfigurationServiceFactory.create(
                configDir, FIXED_CLOCK, SYSTEM_ID, publisher);
        // Schemas are registered on the returned registry — the SAME instance the
        // service validates against (the composition root does this for the
        // automation schema).
        assembly.schemaRegistry().registerCoreSchema("demo", DEMO_SCHEMA);
        Files.writeString(configDir.resolve("homesynapse.yaml"), """
                demo:
                  enabled: true
                """);

        ConfigModel model = assembly.service().load();

        assertThat(model).isNotNull();
        // A successful load proves the registry's (major, minor) matched the
        // service's declared pair — a mismatch fails every load (const mismatch).
        assertThat(model.configSchemaMajor())
                .isEqualTo(ConfigurationServiceFactory.CONFIG_SCHEMA_MAJOR);
        assertThat(model.configSchemaMinor())
                .isEqualTo(ConfigurationServiceFactory.CONFIG_SCHEMA_MINOR);
    }

    @Test
    @DisplayName("getCurrentModel returns the loaded model after load")
    void currentModelAfterLoad() throws Exception {
        ConfigurationServiceFactory.Assembly assembly = ConfigurationServiceFactory.create(
                configDir, FIXED_CLOCK, SYSTEM_ID, publisher);
        assembly.schemaRegistry().registerCoreSchema("demo", DEMO_SCHEMA);
        Files.writeString(configDir.resolve("homesynapse.yaml"), """
                demo:
                  enabled: true
                """);

        ConfigModel loaded = assembly.service().load();
        ConfigModel current = assembly.service().getCurrentModel();

        assertThat(current).isNotNull().isEqualTo(loaded);
    }
}
