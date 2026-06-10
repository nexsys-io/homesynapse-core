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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Tests for {@link YamlLoader} — stage 1–2 of the loading pipeline
 * (Doc 06 §3.1): safe-by-default YAML 1.2 parsing plus the AMD-71
 * one-level {@code !include} mechanism.
 *
 * <p>YAML 1.2 semantics (LTD-09): {@code NO} and {@code on} are plain
 * strings, never booleans — the YAML 1.1 coercion footgun is structurally
 * absent. Unknown tags are FATAL because snakeyaml-engine's safe
 * constructor set never instantiates arbitrary Java types.</p>
 *
 * <p>Layout, traversal-guard, and compose-after-merge behaviour is covered
 * end-to-end in {@link ConfigLayoutTest}; this class exercises the loader
 * in isolation.</p>
 */
@DisplayName("YamlLoader (Doc 06 §3.1 stages 1-2, AMD-71 !include)")
class YamlLoaderTest {

    @TempDir
    Path configDir;

    /** Creates a new test instance. */
    YamlLoaderTest() {
        // Explicit constructor per -Xlint:all -Werror requirement.
    }

    private YamlLoader.Result load() {
        return new YamlLoader(configDir).load();
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
    // Construction
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("null configDir is rejected")
    void nullConfigDirRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> new YamlLoader(null))
                .withMessageContaining("configDir");
    }

    // ──────────────────────────────────────────────────────────────────
    // Stage 1 — file read (INV-CE-02 zero-config)
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Stage 1 — file read")
    class FileReadTests {

        /** Creates a new test instance. */
        FileReadTests() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Test
        @DisplayName("absent root document yields an empty map, no issues (INV-CE-02)")
        void absentFileYieldsEmptyDocument() {
            YamlLoader.Result result = load();

            assertThat(result.document()).isEmpty();
            assertThat(result.issues()).isEmpty();
        }

        @Test
        @DisplayName("empty root document yields an empty map, no issues (INV-CE-02)")
        void emptyFileYieldsEmptyDocument() throws IOException {
            writeRoot("");

            YamlLoader.Result result = load();

            assertThat(result.document()).isEmpty();
            assertThat(result.issues()).isEmpty();
        }

        @Test
        @DisplayName("comment-only root document yields an empty map, no issues")
        void commentOnlyFileYieldsEmptyDocument() throws IOException {
            writeRoot("# no configuration yet\n");

            YamlLoader.Result result = load();

            assertThat(result.document()).isEmpty();
            assertThat(result.issues()).isEmpty();
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Stage 2 — YAML 1.2 parse semantics (LTD-09)
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Stage 2 — YAML 1.2 parse")
    class ParseSemanticsTests {

        /** Creates a new test instance. */
        ParseSemanticsTests() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Test
        @DisplayName("YAML 1.2: NO and on are strings, true is boolean (LTD-09)")
        void yaml12ScalarSemantics() throws IOException {
            writeRoot("""
                    section:
                      answer: NO
                      switch: on
                      flag: true
                      count: 42
                    """);

            YamlLoader.Result result = load();

            assertThat(result.issues()).isEmpty();
            @SuppressWarnings("unchecked")
            Map<String, Object> section =
                    (Map<String, Object>) result.document().get("section");
            assertThat(section.get("answer")).isEqualTo("NO");
            assertThat(section.get("switch")).isEqualTo("on");
            assertThat(section.get("flag")).isEqualTo(Boolean.TRUE);
            assertThat(section.get("count")).isEqualTo(42);
        }

        @Test
        @DisplayName("null-valued keys are dropped (null means unset; defaults apply)")
        void nullValuedKeysDropped() throws IOException {
            writeRoot("""
                    section:
                      present: 1
                      unset: ~
                    """);

            YamlLoader.Result result = load();

            assertThat(result.issues()).isEmpty();
            @SuppressWarnings("unchecked")
            Map<String, Object> section =
                    (Map<String, Object>) result.document().get("section");
            assertThat(section).containsOnlyKeys("present");
        }

        @Test
        @DisplayName("syntax error is a FATAL issue with the YAML line number")
        void syntaxErrorIsFatal() throws IOException {
            writeRoot("""
                    section:
                      bad: [unclosed
                    """);

            YamlLoader.Result result = load();

            assertThat(result.document()).isEmpty();
            assertThat(result.issues()).hasSize(1);
            ConfigIssue issue = result.issues().get(0);
            assertThat(issue.severity()).isEqualTo(Severity.FATAL);
            assertThat(issue.yamlLine()).isNotNull();
        }

        @Test
        @DisplayName("unknown tag is a FATAL issue (safe-by-default, no Java instantiation)")
        void unknownTagIsFatal() throws IOException {
            writeRoot("""
                    section:
                      value: !!java.lang.Runtime {}
                    """);

            YamlLoader.Result result = load();

            assertThat(result.document()).isEmpty();
            assertThat(result.issues()).hasSize(1);
            assertThat(result.issues().get(0).severity()).isEqualTo(Severity.FATAL);
        }

        @Test
        @DisplayName("unresolved !secret tag is FATAL in M6.1 (resolution lands with the M6.2 SecretStore)")
        void secretTagIsFatalUntilM62() throws IOException {
            writeRoot("""
                    section:
                      token: !secret api_token
                    """);

            YamlLoader.Result result = load();

            assertThat(result.issues()).hasSize(1);
            assertThat(result.issues().get(0).severity()).isEqualTo(Severity.FATAL);
        }

        @Test
        @DisplayName("duplicate mapping keys are FATAL (engine safe default)")
        void duplicateKeysAreFatal() throws IOException {
            writeRoot("""
                    section:
                      key: 1
                      key: 2
                    """);

            YamlLoader.Result result = load();

            assertThat(result.document()).isEmpty();
            assertThat(result.issues()).hasSize(1);
            assertThat(result.issues().get(0).severity()).isEqualTo(Severity.FATAL);
        }

        @Test
        @DisplayName("non-mapping root document is FATAL")
        void nonMappingRootIsFatal() throws IOException {
            writeRoot("""
                    - just
                    - a
                    - list
                    """);

            YamlLoader.Result result = load();

            assertThat(result.document()).isEmpty();
            assertThat(result.issues()).hasSize(1);
            ConfigIssue issue = result.issues().get(0);
            assertThat(issue.severity()).isEqualTo(Severity.FATAL);
            assertThat(issue.message()).contains("mapping");
        }

        @Test
        @DisplayName("non-string mapping key is FATAL")
        void nonStringKeyIsFatal() throws IOException {
            writeRoot("""
                    section:
                      42: numeric-key
                    """);

            YamlLoader.Result result = load();

            assertThat(result.document()).isEmpty();
            assertThat(result.issues()).hasSize(1);
            ConfigIssue issue = result.issues().get(0);
            assertThat(issue.severity()).isEqualTo(Severity.FATAL);
            assertThat(issue.message()).contains("string");
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // AMD-71 §2.2 — !include splice (layout cases in ConfigLayoutTest)
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("!include splice")
    class IncludeTests {

        /** Creates a new test instance. */
        IncludeTests() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Test
        @DisplayName("!include splices the included document in place")
        void includeSplicesDocument() throws IOException {
            writeIntegration("zigbee.yaml", """
                    channel: 20
                    network_key: present
                    """);
            writeRoot("""
                    integrations:
                      zigbee: !include integrations/zigbee.yaml
                    """);

            YamlLoader.Result result = load();

            assertThat(result.issues()).isEmpty();
            @SuppressWarnings("unchecked")
            Map<String, Object> integrations =
                    (Map<String, Object>) result.document().get("integrations");
            @SuppressWarnings("unchecked")
            Map<String, Object> zigbee =
                    (Map<String, Object>) integrations.get("zigbee");
            assertThat(zigbee).containsEntry("channel", 20)
                    .containsEntry("network_key", "present");
        }

        @Test
        @DisplayName("!include of a missing file is FATAL, naming the path")
        void includeOfMissingFileIsFatal() throws IOException {
            Files.createDirectories(configDir.resolve("integrations"));
            writeRoot("""
                    integrations:
                      ghost: !include integrations/ghost.yaml
                    """);

            YamlLoader.Result result = load();

            assertThat(result.document()).isEmpty();
            assertThat(result.issues()).hasSize(1);
            ConfigIssue issue = result.issues().get(0);
            assertThat(issue.severity()).isEqualTo(Severity.FATAL);
            assertThat(issue.message()).contains("integrations/ghost.yaml");
        }

        @Test
        @DisplayName("!include with a non-scalar argument is FATAL")
        void includeWithNonScalarArgumentIsFatal() throws IOException {
            Files.createDirectories(configDir.resolve("integrations"));
            writeRoot("""
                    integrations:
                      bad: !include [not, a, path]
                    """);

            YamlLoader.Result result = load();

            assertThat(result.document()).isEmpty();
            assertThat(result.issues()).hasSize(1);
            assertThat(result.issues().get(0).severity()).isEqualTo(Severity.FATAL);
        }
    }
}
