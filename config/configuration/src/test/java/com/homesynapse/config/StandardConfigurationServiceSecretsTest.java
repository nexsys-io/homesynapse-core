/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.config;

import com.homesynapse.event.ConfigurationValidationException;
import com.homesynapse.platform.identity.SystemId;
import com.homesynapse.platform.identity.Ulid;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Service-level tests for the M6.2 stage-3 tag threading (DP-10): the
 * {@code !secret}/{@code !env} resolution must flow through BOTH the
 * {@code load()} and {@code reload()} pipelines (M6.4 landed first, so the
 * reload re-parse is live), while the {@code write()} path stays
 * NON-resolving and rejects tag-bearing documents fail-closed
 * (INV-SE-03/§12.3 — a resolved tag would be re-emitted as plaintext).
 *
 * <p>Loader-level tag mechanics live in {@link YamlLoaderSecretEnvTest};
 * the encrypted store itself in {@link SecretStoreTest}.</p>
 */
@DisplayName("StandardConfigurationService (M6.2 secret/env threading)")
class StandardConfigurationServiceSecretsTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-06-11T00:00:00Z"), ZoneOffset.UTC);
    private static final SystemId SYSTEM_ID = SystemId.of(new Ulid(7L, 7L));

    private static final String SECURITY_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "api_token": { "type": "string" },
                "mqtt_url": { "type": "string", "default": "tcp://localhost:1883" }
              },
              "additionalProperties": false
            }
            """;

    @TempDir
    Path configDir;

    private final RecordingEventPublisher publisher = new RecordingEventPublisher();
    private final Map<String, String> environment = new HashMap<>();

    /** Creates a new test instance. */
    StandardConfigurationServiceSecretsTest() {
        // Explicit constructor per -Xlint:all -Werror requirement.
    }

    private SecretStore secretStore() {
        return SecretStore.create(configDir,
                ScopeKeyManager.create(configDir, FIXED_CLOCK), FIXED_CLOCK);
    }

    private StandardConfigurationService service(SecretStore secretStore) {
        StandardSchemaRegistry registry = new StandardSchemaRegistry(1, 0);
        registry.registerCoreSchema("security", SECURITY_SCHEMA);
        return new StandardConfigurationService(
                configDir, 1, 0, FIXED_CLOCK, SYSTEM_ID, publisher, registry,
                new JsonSchemaCompositeValidator(), List.of(), List.of(),
                secretStore, environment::get);
    }

    private void writeRoot(String yaml) throws IOException {
        Files.writeString(configDir.resolve("homesynapse.yaml"), yaml);
    }

    @Test
    @DisplayName("load resolves !secret and !env into the model; resolved"
            + " values exist only in memory")
    void loadResolvesTagsIntoModel() throws Exception {
        SecretStore store = secretStore();
        store.set("api_token", "tok-secret-1");
        environment.put("HS_MQTT_URL", "tcp://broker.local:8883");
        writeRoot("""
                security:
                  api_token: !secret api_token
                  mqtt_url: !env HS_MQTT_URL:tcp://fallback:1883
                """);

        ConfigModel model = service(store).load();

        ConfigSection security = model.sections().get("security");
        assertThat(security.values())
                .containsEntry("api_token", "tok-secret-1")
                .containsEntry("mqtt_url", "tcp://broker.local:8883");
        // The on-disk document still carries the tags, not the values
        // (§3.4 — resolution lives in the parse tree only).
        assertThat(Files.readString(configDir.resolve("homesynapse.yaml")))
                .contains("!secret api_token")
                .doesNotContain("tok-secret-1");
    }

    @Test
    @DisplayName("reload re-resolves tags through the same pipeline (M6.4"
            + " sequencing note: BOTH paths)")
    void reloadResolvesTags() throws Exception {
        SecretStore store = secretStore();
        store.set("api_token", "before-rotation");
        writeRoot("""
                security:
                  api_token: !secret api_token
                """);
        StandardConfigurationService service = service(store);
        service.load();

        store.set("api_token", "after-rotation");
        ReloadResult result = service.reload();

        assertThat(result.newModel().sections().get("security").values())
                .containsEntry("api_token", "after-rotation");
        assertThat(service.getCurrentModel()).isSameAs(result.newModel());
    }

    @Test
    @DisplayName("write rejects a tag-bearing document fail-closed: file and"
            + " active model untouched (INV-SE-03)")
    void writeRejectsTagBearingDocument() throws Exception {
        SecretStore store = secretStore();
        store.set("api_token", "tok-secret-1");
        writeRoot("""
                security:
                  api_token: !secret api_token
                """);
        StandardConfigurationService service = service(store);
        ConfigModel loaded = service.load();
        byte[] fileBefore = Files.readAllBytes(configDir.resolve("homesynapse.yaml"));

        assertThatThrownBy(() -> service.write(
                List.of(new ConfigMutation("security", "mqtt_url",
                        "tcp://new:1883")),
                loaded.fileModifiedAt()))
                .isInstanceOf(ConfigurationValidationException.class)
                .hasMessageContaining("write path");

        assertThat(Files.readAllBytes(configDir.resolve("homesynapse.yaml")))
                .isEqualTo(fileBefore);
        assertThat(service.getCurrentModel()).isSameAs(loaded);
    }

    @Test
    @DisplayName("a missing secret key fails the load FATAL, naming the key"
            + " only (LTD-15)")
    void missingSecretKeyFailsLoad() throws Exception {
        SecretStore store = secretStore();
        store.set("present_key", "MUST-NOT-LEAK");
        writeRoot("""
                security:
                  api_token: !secret rotated_away
                """);

        assertThatThrownBy(() -> service(store).load())
                .isInstanceOf(ConfigurationLoadException.class)
                .hasMessageContaining("rotated_away")
                .satisfies(e -> assertThat(e.getMessage())
                        .doesNotContain("MUST-NOT-LEAK"));
    }

    @Test
    @DisplayName("a tag-free zero-config load touches no key files"
            + " (INV-CE-02)")
    void zeroConfigLoadTouchesNoKeyFiles() throws Exception {
        service(secretStore()).load();

        assertThat(configDir.resolve(".root-key")).doesNotExist();
        assertThat(configDir.resolve("scope_keys.json")).doesNotExist();
        assertThat(configDir.resolve("secrets.enc")).doesNotExist();
    }
}
