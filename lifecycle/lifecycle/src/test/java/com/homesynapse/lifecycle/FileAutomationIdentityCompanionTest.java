/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.homesynapse.automation.AutomationIdentityCompanion;
import com.homesynapse.automation.CompanionAutomationIdentityStore;
import com.homesynapse.platform.identity.AutomationId;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * AUTO-ID-1 — {@link FileAutomationIdentityCompanion}: the BYTES of
 * {@code automations.ids.yaml} (Doc 07 §4.1; AMD-93 §2.3) — absent reads empty, a round
 * trip is byte-stable and parses through the YAML library itself, the temp file never
 * survives, a malformed file fails closed naming the path. The companion's POLICY
 * (minting, retention, the shape) is {@code core/automation}'s
 * {@code CompanionAutomationIdentityStoreTest}; the last test here runs the two together
 * over one file.
 *
 * <p>Time is injected via {@code Clock.fixed}; nothing here reads a wall clock.</p>
 */
@DisplayName("FileAutomationIdentityCompanion — the bytes of automations.ids.yaml (AUTO-ID-1)")
final class FileAutomationIdentityCompanionTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final String FILE_NAME = "automations.ids.yaml";
    private static final String HEADER =
            "# automations.ids.yaml — engine-managed (Doc 07 §4.1; AMD-93 §2.3). Do not edit.";

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    FileAutomationIdentityCompanionTest() {
    }

    @Test
    @DisplayName("an ABSENT file reads empty — the first boot")
    void absentFile_readsEmpty(@TempDir Path dir) {
        assertThat(new FileAutomationIdentityCompanion(dir.resolve(FILE_NAME)).read()).isEmpty();
    }

    @Test
    @DisplayName("the R2 bytes: the header comment verbatim, then the document in ITS order — "
            + "schema_version first, block style, an index key quoted by the library")
    void replace_writesTheR2Bytes(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(FILE_NAME);

        new FileAutomationIdentityCompanion(file).replace(r2Document());

        assertThat(file).isRegularFile();
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo(HEADER + "\n"
                + "schema_version: 1\n"
                + "automations:\n"
                + "  hero-light:\n"
                + "    id: 01M1PRQN03X8H4MNEZQ62F76F1\n"
                + "    last_seen: 2026-09-20T12:00:00Z\n"
                + "    triggers:\n"
                + "      '0': 01M1PRQN03X8H4MNEZQ62F76F2\n");
    }

    @Test
    @DisplayName("P6: a round trip returns the document, parses through the YAML library "
            + "itself, and is byte-stable — read() then replace() leaves the bytes identical")
    void roundTrip_isByteStable_andParsesThroughTheLibrary(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(FILE_NAME);
        FileAutomationIdentityCompanion companion = new FileAutomationIdentityCompanion(file);

        companion.replace(r2Document());
        assertThat(file).isRegularFile();
        byte[] written = Files.readAllBytes(file);

        Optional<Map<String, Object>> read = companion.read();
        assertThat(read).contains(r2Document());
        assertThat(new Load(LoadSettings.builder().build())
                .loadFromString(Files.readString(file, StandardCharsets.UTF_8)))
                .as("the file is YAML by the library's own parse, header comment and all")
                .isEqualTo(r2Document());

        companion.replace(read.orElseThrow());
        assertThat(Files.readAllBytes(file)).isEqualTo(written);
        new FileAutomationIdentityCompanion(file).replace(r2Document());
        assertThat(Files.readAllBytes(file)).isEqualTo(written);
    }

    @Test
    @DisplayName("the temp file never survives: not after a write, not after a write that FAILS "
            + "— and a failed write leaves the target as it was, naming the path")
    void tempFile_neverSurvives(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(FILE_NAME);
        new FileAutomationIdentityCompanion(file).replace(r2Document());
        assertThat(namesIn(dir)).containsExactly(FILE_NAME);

        // A non-empty DIRECTORY at the target: the atomic move fails on every platform.
        Path blockedDir = Files.createDirectories(dir.resolve("blocked"));
        Path blocked = Files.createDirectories(blockedDir.resolve(FILE_NAME));
        Files.writeString(blocked.resolve("occupant"), "x");

        assertThatThrownBy(() -> new FileAutomationIdentityCompanion(blocked)
                .replace(r2Document()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(blocked.toString());
        assertThat(namesIn(blockedDir)).containsExactly(FILE_NAME);
        assertThat(namesIn(blocked)).containsExactly("occupant");
    }

    @Test
    @DisplayName("a MALFORMED file fails closed with an IllegalStateException naming the path, "
            + "and its bytes are untouched")
    void malformedFile_failsClosed_namingThePath(@TempDir Path dir) throws IOException {
        Map<String, String> malformed = new LinkedHashMap<>();
        malformed.put("an unterminated flow sequence", "automations: [\n  unterminated\n");
        malformed.put("a sequence at the root", "- hero-light\n- porch\n");
        malformed.put("a scalar at the root", "hero-light\n");
        malformed.put("an empty file", "");
        malformed.put("a comment-only file", HEADER + "\n");
        malformed.put("a duplicate key", "schema_version: 1\nschema_version: 1\n");
        malformed.put("a mapping as a key", "? {a: b}\n: c\n");

        SoftAssertions softly = new SoftAssertions();
        int n = 0;
        for (Map.Entry<String, String> entry : malformed.entrySet()) {
            Path file = Files.createDirectories(dir.resolve("case" + n++)).resolve(FILE_NAME);
            Files.writeString(file, entry.getValue(), StandardCharsets.UTF_8);

            softly.assertThatThrownBy(() -> new FileAutomationIdentityCompanion(file).read())
                    .as("read() of %s", entry.getKey())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(file.toString());
            softly.assertThat(Files.readString(file, StandardCharsets.UTF_8))
                    .as("the bytes of %s after the fail-closed read", entry.getKey())
                    .isEqualTo(entry.getValue());
        }
        Path directory = Files.createDirectories(dir.resolve("dir-case").resolve(FILE_NAME));
        softly.assertThatThrownBy(() -> new FileAutomationIdentityCompanion(directory).read())
                .as("read() of a directory at the path")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(directory.toString());
        softly.assertAll();
    }

    @Test
    @DisplayName("a scalar key the YAML typed (an unquoted index) reaches the store as a String "
            + "— the seam's document is string-keyed")
    void scalarKeys_areStrings(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(FILE_NAME);
        Files.writeString(file, "schema_version: 1\nautomations:\n  hero-light:\n"
                + "    triggers:\n      0: 01M1PRQN03X8H4MNEZQ62F76F2\n", StandardCharsets.UTF_8);

        Map<String, Object> document =
                new FileAutomationIdentityCompanion(file).read().orElseThrow();

        Object triggers = ((Map<?, ?>) ((Map<?, ?>) document.get("automations"))
                .get("hero-light")).get("triggers");
        assertThat(new ArrayList<Object>(((Map<?, ?>) triggers).keySet()))
                .as("the key is the String \"0\", never the Integer the YAML resolved")
                .containsExactly("0");
    }

    @Test
    @DisplayName("T2 over the FILE: a second store and companion over the same file return the "
            + "same ids, and a load that changes nothing neither writes nor moves a byte (P3/P6)")
    void storeOverTheFile_isDurable_andWritesOnlyWhenChanged(@TempDir Path dir)
            throws IOException {
        Path file = dir.resolve(FILE_NAME);
        CountingCompanion firstBoot = new CountingCompanion(
                new FileAutomationIdentityCompanion(file));
        CompanionAutomationIdentityStore first =
                new CompanionAutomationIdentityStore(firstBoot, FIXED_CLOCK);
        first.beginLoad();
        AutomationId hero = first.automationIdFor("hero-motion");
        String trigger = first.generatedTriggerIdFor("hero-motion", 0);
        first.endLoad();
        assertThat(firstBoot.replaces).isEqualTo(1);
        assertThat(file).isRegularFile();
        byte[] written = Files.readAllBytes(file);
        assertThat(new String(written, StandardCharsets.UTF_8))
                .startsWith(HEADER + "\n")
                .contains("hero-motion")
                .contains(hero.toString());

        CountingCompanion secondBoot = new CountingCompanion(
                new FileAutomationIdentityCompanion(file));
        CompanionAutomationIdentityStore second =
                new CompanionAutomationIdentityStore(secondBoot, FIXED_CLOCK);
        second.beginLoad();
        assertThat(second.automationIdFor("hero-motion")).isEqualTo(hero);
        assertThat(second.generatedTriggerIdFor("hero-motion", 0)).isEqualTo(trigger);
        second.endLoad();

        assertThat(secondBoot.replaces).as("a load that changed nothing writes nothing").isZero();
        assertThat(Files.readAllBytes(file)).isEqualTo(written);
        assertThat(namesIn(dir)).containsExactly(FILE_NAME);
    }

    @Test
    @DisplayName("slugs the YAML would type, quote or fold — blank, numeric, 'true', 'null', a "
            + "colon, a hash, non-ASCII, longer than a simple key — survive the file: the "
            + "second boot reads what the first wrote and returns the same ids")
    void oddSlugs_surviveTheFile(@TempDir Path dir) {
        Path file = dir.resolve(FILE_NAME);
        List<String> slugs = List.of("", " ", "123", "0", "true", "null", "~", "a: b", "#hash",
                "'quoted'", "  spaced  ", "ünï-ß", "x".repeat(200));
        CompanionAutomationIdentityStore first = new CompanionAutomationIdentityStore(
                new FileAutomationIdentityCompanion(file), FIXED_CLOCK);
        first.beginLoad();
        List<AutomationId> minted = slugs.stream().map(first::automationIdFor).toList();
        first.endLoad();
        assertThat(file).isRegularFile();

        CompanionAutomationIdentityStore second = new CompanionAutomationIdentityStore(
                new FileAutomationIdentityCompanion(file), FIXED_CLOCK);
        second.beginLoad();
        assertThat(slugs.stream().map(second::automationIdFor).toList()).isEqualTo(minted);
        second.endLoad();
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    /** R2's example document, built in the order the store builds it. */
    private static Map<String, Object> r2Document() {
        Map<String, Object> triggers = new LinkedHashMap<>();
        triggers.put("0", "01M1PRQN03X8H4MNEZQ62F76F2");
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", "01M1PRQN03X8H4MNEZQ62F76F1");
        entry.put("last_seen", "2026-09-20T12:00:00Z");
        entry.put("triggers", triggers);
        Map<String, Object> automations = new LinkedHashMap<>();
        automations.put("hero-light", entry);
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("schema_version", 1);
        document.put("automations", automations);
        return document;
    }

    private static List<String> namesIn(Path dir) throws IOException {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    /** Counts the writes that reach the file. */
    private static final class CountingCompanion implements AutomationIdentityCompanion {

        private final AutomationIdentityCompanion delegate;
        private int replaces;

        private CountingCompanion(AutomationIdentityCompanion delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<Map<String, Object>> read() {
            return delegate.read();
        }

        @Override
        public void replace(Map<String, Object> document) {
            replaces++;
            delegate.replace(document);
        }

        @Override
        public String toString() {
            return delegate.toString();
        }
    }
}
