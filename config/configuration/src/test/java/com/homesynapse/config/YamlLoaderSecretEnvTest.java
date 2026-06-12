/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.config;

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
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the {@link YamlLoader} stage-3 {@code !secret}/{@code !env}
 * tag resolution (Doc 06 §3.1/§3.4 — M6.2, DP-10).
 *
 * <p>Covers: resolution in the root AND in included documents, the
 * missing-key/unset-variable FATALs that name the key but never any value
 * (LTD-15/§12.3), multi-failure collection in one pass, the
 * {@code VAR:default} form, the CoreSchema {@code ~}-is-null regression
 * pin under the new constructor, unchanged {@code !include} behavior
 * (nested include still FATAL), the INV-CE-02 zero-touch property over
 * the REAL key machinery, and the write-path form's fail-closed tag
 * rejection (INV-SE-03).</p>
 */
@DisplayName("YamlLoader !secret/!env stage-3 resolution (Doc 06 §3.1, M6.2)")
class YamlLoaderSecretEnvTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-06-11T00:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path configDir;

    private final MapSecretStore secrets = new MapSecretStore();
    private final Map<String, String> environment = new HashMap<>();

    /** Creates a new test instance. */
    YamlLoaderSecretEnvTest() {
        // Explicit constructor per -Xlint:all -Werror requirement.
    }

    private YamlLoader.Result load() {
        return new YamlLoader(configDir, secrets, environment::get).load();
    }

    private void writeRoot(String yaml) throws IOException {
        Files.writeString(configDir.resolve("homesynapse.yaml"), yaml);
    }

    private void writeIntegration(String fileName, String yaml) throws IOException {
        Path dir = configDir.resolve("integrations");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(fileName), yaml);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(YamlLoader.Result result,
                                               String key) {
        return (Map<String, Object>) result.document().get(key);
    }

    // ──────────────────────────────────────────────────────────────────
    // !secret resolution
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("!secret")
    class SecretTagTests {

        /** Creates a new test instance. */
        SecretTagTests() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Test
        @DisplayName("resolves in the root document")
        void resolvesInRootDocument() throws IOException {
            secrets.entries.put("api_token", "tok-12345");
            writeRoot("""
                    security:
                      api_token: !secret api_token
                    """);

            YamlLoader.Result result = load();

            assertThat(result.issues()).isEmpty();
            assertThat(section(result, "security"))
                    .containsEntry("api_token", "tok-12345");
        }

        @Test
        @DisplayName("resolves in an included document (DP-10)")
        void resolvesInIncludedDocument() throws IOException {
            secrets.entries.put("zigbee_network_key", "0xCAFE");
            writeIntegration("zigbee.yaml", """
                    channel: 20
                    network_key: !secret zigbee_network_key
                    """);
            writeRoot("""
                    integrations:
                      zigbee: !include integrations/zigbee.yaml
                    """);

            YamlLoader.Result result = load();

            assertThat(result.issues()).isEmpty();
            @SuppressWarnings("unchecked")
            Map<String, Object> zigbee = (Map<String, Object>)
                    section(result, "integrations").get("zigbee");
            assertThat(zigbee).containsEntry("network_key", "0xCAFE")
                    .containsEntry("channel", 20);
        }

        @Test
        @DisplayName("missing key is FATAL naming the KEY, never any value"
                + " (LTD-15)")
        void missingKeyIsFatalNamingKeyOnly() throws IOException {
            secrets.entries.put("other_key", "MUST-NOT-LEAK");
            writeRoot("""
                    security:
                      api_token: !secret nonexistent_key
                    """);

            YamlLoader.Result result = load();

            assertThat(result.document()).isEmpty();
            assertThat(result.issues()).hasSize(1);
            ConfigIssue issue = result.issues().get(0);
            assertThat(issue.severity()).isEqualTo(Severity.FATAL);
            assertThat(issue.path()).isEqualTo("nonexistent_key");
            assertThat(issue.message()).contains("nonexistent_key");
            assertThat(issue.message()).doesNotContain("MUST-NOT-LEAK");
        }

        @Test
        @DisplayName("a non-scalar argument is FATAL")
        void nonScalarArgumentIsFatal() throws IOException {
            writeRoot("""
                    security:
                      api_token: !secret [not, a, key]
                    """);

            YamlLoader.Result result = load();

            assertThat(result.document()).isEmpty();
            assertThat(result.issues()).hasSize(1);
            assertThat(result.issues().get(0).severity()).isEqualTo(Severity.FATAL);
        }

        @Test
        @DisplayName("every missing tag is collected in one pass, not just"
                + " the first (DP-10)")
        void multipleFailuresCollectedInOnePass() throws IOException {
            writeRoot("""
                    security:
                      first: !secret missing_one
                      second: !secret missing_two
                      third: !env UNSET_VARIABLE
                    """);

            YamlLoader.Result result = load();

            assertThat(result.document()).isEmpty();
            assertThat(result.issues()).hasSize(3);
            assertThat(result.issues())
                    .allSatisfy(issue -> assertThat(issue.severity())
                            .isEqualTo(Severity.FATAL));
            assertThat(result.issues())
                    .extracting(ConfigIssue::path)
                    .containsExactlyInAnyOrder("missing_one", "missing_two",
                            "UNSET_VARIABLE");
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // !env resolution
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("!env")
    class EnvTagTests {

        /** Creates a new test instance. */
        EnvTagTests() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Test
        @DisplayName("resolves a set variable")
        void resolvesSetVariable() throws IOException {
            environment.put("HS_MQTT_HOST", "broker.local");
            writeRoot("""
                    mqtt:
                      host: !env HS_MQTT_HOST
                    """);

            YamlLoader.Result result = load();

            assertThat(result.issues()).isEmpty();
            assertThat(section(result, "mqtt"))
                    .containsEntry("host", "broker.local");
        }

        @Test
        @DisplayName("VAR:default — the default applies when the variable is"
                + " absent, and may itself contain colons")
        void defaultAppliesWhenAbsent() throws IOException {
            writeRoot("""
                    mqtt:
                      url: !env HS_MQTT_URL:tcp://broker.local:1883
                      port: !env HS_MQTT_PORT:1883
                    """);

            YamlLoader.Result result = load();

            assertThat(result.issues()).isEmpty();
            assertThat(section(result, "mqtt"))
                    .containsEntry("url", "tcp://broker.local:1883")
                    .containsEntry("port", "1883");
        }

        @Test
        @DisplayName("VAR:default — the set variable wins over the default")
        void setVariableWinsOverDefault() throws IOException {
            environment.put("HS_MQTT_PORT", "8883");
            writeRoot("""
                    mqtt:
                      port: !env HS_MQTT_PORT:1883
                    """);

            YamlLoader.Result result = load();

            assertThat(result.issues()).isEmpty();
            assertThat(section(result, "mqtt")).containsEntry("port", "8883");
        }

        @Test
        @DisplayName("absent variable without a default is FATAL naming the"
                + " variable")
        void absentWithoutDefaultIsFatal() throws IOException {
            writeRoot("""
                    mqtt:
                      host: !env HS_NEVER_SET
                    """);

            YamlLoader.Result result = load();

            assertThat(result.document()).isEmpty();
            assertThat(result.issues()).hasSize(1);
            ConfigIssue issue = result.issues().get(0);
            assertThat(issue.severity()).isEqualTo(Severity.FATAL);
            assertThat(issue.path()).isEqualTo("HS_NEVER_SET");
            assertThat(issue.message()).contains("HS_NEVER_SET");
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Pre-existing behavior pins (regressions the new ctor must not move)
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("regression pins")
    class RegressionPinTests {

        /** Creates a new test instance. */
        RegressionPinTests() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Test
        @DisplayName("CoreSchema still effective: ~ is null = unset, key"
                + " dropped (DP-12 lockstep)")
        void coreSchemaStillEffective() throws IOException {
            secrets.entries.put("api_token", "tok");
            writeRoot("""
                    section:
                      present: 1
                      unset: ~
                      token: !secret api_token
                    """);

            YamlLoader.Result result = load();

            assertThat(result.issues()).isEmpty();
            assertThat(section(result, "section"))
                    .containsOnlyKeys("present", "token");
        }

        @Test
        @DisplayName("nested !include is still FATAL (AMD-71-INV-02)")
        void nestedIncludeStillFatal() throws IOException {
            writeIntegration("outer.yaml", """
                    inner: !include integrations/inner.yaml
                    """);
            writeIntegration("inner.yaml", "key: value\n");
            writeRoot("""
                    integrations:
                      outer: !include integrations/outer.yaml
                    """);

            YamlLoader.Result result = load();

            assertThat(result.document()).isEmpty();
            assertThat(result.issues()).hasSize(1);
            assertThat(result.issues().get(0).severity()).isEqualTo(Severity.FATAL);
            assertThat(result.issues().get(0).message()).contains("one level");
        }

        @Test
        @DisplayName("a tag-free load over the REAL key machinery touches no"
                + " key files (INV-CE-02)")
        void tagFreeLoadTouchesNoKeyFiles() throws IOException {
            writeRoot("""
                    event_bus:
                      queue_capacity: 2048
                    """);
            ScopeKeyManager keyManager =
                    ScopeKeyManager.create(configDir, FIXED_CLOCK);
            SecretStore realStore =
                    SecretStore.create(configDir, keyManager, FIXED_CLOCK);

            YamlLoader.Result result = new YamlLoader(
                    configDir, realStore, environment::get).load();

            assertThat(result.issues()).isEmpty();
            assertThat(configDir.resolve(".root-key")).doesNotExist();
            assertThat(configDir.resolve("scope_keys.json")).doesNotExist();
            assertThat(configDir.resolve("secrets.enc")).doesNotExist();
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Write-path form (Doc 06 §3.5 — INV-SE-03 fail-closed)
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("write-path form (one-arg constructor)")
    class WritePathFormTests {

        /** Creates a new test instance. */
        WritePathFormTests() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Test
        @DisplayName("!secret is rejected fail-closed — resolving here would"
                + " bake plaintext into the rewritten file")
        void secretTagRejectedFailClosed() throws IOException {
            secrets.entries.put("api_token", "tok");
            writeRoot("""
                    security:
                      api_token: !secret api_token
                    """);

            YamlLoader.Result result = new YamlLoader(configDir).load();

            assertThat(result.document()).isEmpty();
            assertThat(result.issues()).hasSize(1);
            ConfigIssue issue = result.issues().get(0);
            assertThat(issue.severity()).isEqualTo(Severity.FATAL);
            assertThat(issue.message()).contains("write path");
            assertThat(issue.message()).doesNotContain("tok");
        }

        @Test
        @DisplayName("!env is rejected fail-closed under the same stance")
        void envTagRejectedFailClosed() throws IOException {
            environment.put("HS_PORT", "1883");
            writeRoot("""
                    mqtt:
                      port: !env HS_PORT
                    """);

            YamlLoader.Result result = new YamlLoader(configDir).load();

            assertThat(result.document()).isEmpty();
            assertThat(result.issues()).hasSize(1);
            assertThat(result.issues().get(0).severity()).isEqualTo(Severity.FATAL);
            assertThat(result.issues().get(0).message()).contains("write path");
        }
    }

    /**
     * Map-backed {@link SecretStore} stub — the loader tests exercise tag
     * resolution, not the encrypted store (covered by
     * {@link SecretStoreTest}).
     */
    private static final class MapSecretStore implements SecretStore {

        final Map<String, String> entries = new HashMap<>();

        /** Creates an empty stub store. */
        MapSecretStore() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Override
        public String resolve(String key) {
            String value = entries.get(key);
            if (value == null) {
                throw new IllegalArgumentException(
                        "secret key is not in the secret store: " + key);
            }
            return value;
        }

        @Override
        public void set(String key, String value) {
            entries.put(key, value);
        }

        @Override
        public void setAll(Map<String, String> secrets) {
            if (secrets.isEmpty()) {
                throw new IllegalArgumentException("setAll requires entries");
            }
            entries.putAll(secrets);
        }

        @Override
        public void remove(String key) {
            if (entries.remove(key) == null) {
                throw new IllegalArgumentException(
                        "secret key is not in the secret store: " + key);
            }
        }

        @Override
        public Set<String> list() {
            return Set.copyOf(new LinkedHashSet<>(entries.keySet()));
        }
    }
}
